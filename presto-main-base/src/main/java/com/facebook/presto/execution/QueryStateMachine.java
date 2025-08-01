/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.common.ErrorCode;
import com.facebook.presto.common.resourceGroups.QueryType;
import com.facebook.presto.common.transaction.TransactionId;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.StatsAndCosts;
import com.facebook.presto.cost.VariableStatsEstimate;
import com.facebook.presto.execution.QueryExecution.QueryOutputInfo;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.memory.VersionedMemoryPoolId;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.server.BasicQueryInfo;
import com.facebook.presto.server.BasicQueryStats;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.analyzer.UpdateInfo;
import com.facebook.presto.spi.connector.ConnectorCommitHandle;
import com.facebook.presto.spi.function.SqlFunctionId;
import com.facebook.presto.spi.function.SqlInvokedFunction;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.spi.resourceGroups.ResourceGroupId;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.spi.security.SelectedRole;
import com.facebook.presto.spi.statistics.ColumnStatistics;
import com.facebook.presto.spi.statistics.TableStatistics;
import com.facebook.presto.sql.planner.CanonicalPlanWithInfo;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.transaction.TransactionInfo;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.Duration;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.facebook.presto.execution.BasicStageExecutionStats.EMPTY_STAGE_STATS;
import static com.facebook.presto.execution.QueryState.DISPATCHING;
import static com.facebook.presto.execution.QueryState.FINISHED;
import static com.facebook.presto.execution.QueryState.FINISHING;
import static com.facebook.presto.execution.QueryState.PLANNING;
import static com.facebook.presto.execution.QueryState.QUEUED;
import static com.facebook.presto.execution.QueryState.RUNNING;
import static com.facebook.presto.execution.QueryState.STARTING;
import static com.facebook.presto.execution.QueryState.TERMINAL_QUERY_STATES;
import static com.facebook.presto.execution.QueryState.WAITING_FOR_PREREQUISITES;
import static com.facebook.presto.execution.QueryState.WAITING_FOR_RESOURCES;
import static com.facebook.presto.execution.StageInfo.getAllStages;
import static com.facebook.presto.memory.LocalMemoryManager.GENERAL_POOL;
import static com.facebook.presto.spi.StandardErrorCode.ALREADY_EXISTS;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_ARGUMENTS;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.facebook.presto.spi.StandardErrorCode.USER_CANCELED;
import static com.facebook.presto.util.Failures.toFailure;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.units.DataSize.succinctBytes;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class QueryStateMachine
{
    private static final Logger QUERY_STATE_LOG = Logger.get(QueryStateMachine.class);

    private final QueryId queryId;
    private final String query;
    private final Optional<String> preparedQuery;
    private final Session session;
    private final URI self;
    private final Optional<QueryType> queryType;
    private final ResourceGroupId resourceGroup;
    private final TransactionManager transactionManager;
    private final Metadata metadata;
    private final QueryOutputManager outputManager;

    private final AtomicReference<VersionedMemoryPoolId> memoryPool = new AtomicReference<>(new VersionedMemoryPoolId(GENERAL_POOL, 0));

    private final AtomicLong currentUserMemory = new AtomicLong();
    private final AtomicLong peakUserMemory = new AtomicLong();

    // peak of the user + system memory reservation
    private final AtomicLong currentTotalMemory = new AtomicLong();
    private final AtomicLong peakTotalMemory = new AtomicLong();

    private final AtomicLong peakTaskUserMemory = new AtomicLong();
    private final AtomicLong peakTaskTotalMemory = new AtomicLong();
    private final AtomicLong peakNodeTotalMemory = new AtomicLong();

    private final AtomicInteger currentRunningTaskCount = new AtomicInteger();
    private final AtomicInteger peakRunningTaskCount = new AtomicInteger();

    private final QueryStateTimer queryStateTimer;

    private final StateMachine<QueryState> queryState;

    private final AtomicReference<String> setCatalog = new AtomicReference<>();
    private final AtomicReference<String> setSchema = new AtomicReference<>();

    private final Map<String, String> setSessionProperties = new ConcurrentHashMap<>();
    private final Set<String> resetSessionProperties = Sets.newConcurrentHashSet();

    private final Map<String, SelectedRole> setRoles = new ConcurrentHashMap<>();

    private final Map<String, String> addedPreparedStatements = new ConcurrentHashMap<>();
    private final Set<String> deallocatedPreparedStatements = Sets.newConcurrentHashSet();

    private final AtomicReference<TransactionId> startedTransactionId = new AtomicReference<>();
    private final AtomicBoolean clearTransactionId = new AtomicBoolean();

    private final AtomicReference<UpdateInfo> updateInfo = new AtomicReference<>();

    private final AtomicReference<ExecutionFailureInfo> failureCause = new AtomicReference<>();

    private final AtomicReference<StatsAndCosts> planStatsAndCosts = new AtomicReference<>();
    private final AtomicReference<Map<PlanNodeId, PlanNode>> planIdNodeMap = new AtomicReference<>();
    private final AtomicReference<List<CanonicalPlanWithInfo>> planCanonicalInfo = new AtomicReference<>();
    private final AtomicReference<Set<Input>> inputs = new AtomicReference<>(ImmutableSet.of());
    private final AtomicReference<Optional<Output>> output = new AtomicReference<>(Optional.empty());

    private final StateMachine<Optional<QueryInfo>> finalQueryInfo;
    private final AtomicReference<Optional<String>> expandedQuery = new AtomicReference<>(Optional.empty());

    private final Map<SqlFunctionId, SqlInvokedFunction> addedSessionFunctions = new ConcurrentHashMap<>();
    private final Set<SqlFunctionId> removedSessionFunctions = Sets.newConcurrentHashSet();

    private final WarningCollector warningCollector;
    private final AtomicReference<Set<String>> scalarFunctions = new AtomicReference<>(ImmutableSet.of());
    private final AtomicReference<Set<String>> aggregateFunctions = new AtomicReference<>(ImmutableSet.of());
    private final AtomicReference<Set<String>> windowFunctions = new AtomicReference<>(ImmutableSet.of());

    private QueryStateMachine(
            String query,
            Optional<String> preparedQuery,
            Session session,
            URI self,
            ResourceGroupId resourceGroup,
            Optional<QueryType> queryType,
            TransactionManager transactionManager,
            Executor executor,
            Ticker ticker,
            Metadata metadata,
            WarningCollector warningCollector)
    {
        this.query = requireNonNull(query, "query is null");
        this.preparedQuery = requireNonNull(preparedQuery, "preparedQuery is null");
        this.session = requireNonNull(session, "session is null");
        this.queryId = session.getQueryId();
        this.self = requireNonNull(self, "self is null");
        this.resourceGroup = requireNonNull(resourceGroup, "resourceGroup is null");
        this.queryType = requireNonNull(queryType, "queryType is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.queryStateTimer = new QueryStateTimer(ticker);
        this.metadata = requireNonNull(metadata, "metadata is null");

        this.queryState = new StateMachine<>("query " + query, executor, WAITING_FOR_PREREQUISITES, TERMINAL_QUERY_STATES);
        this.finalQueryInfo = new StateMachine<>("finalQueryInfo-" + queryId, executor, Optional.empty());
        this.outputManager = new QueryOutputManager(executor);
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
    }

    /**
     * Created QueryStateMachines must be transitioned to terminal states to clean up resources.
     */
    public static QueryStateMachine begin(
            String query,
            Optional<String> preparedQuery,
            Session session,
            URI self,
            ResourceGroupId resourceGroup,
            Optional<QueryType> queryType,
            boolean transactionControl,
            TransactionManager transactionManager,
            AccessControl accessControl,
            Executor executor,
            Metadata metadata,
            WarningCollector warningCollector)
    {
        return beginWithTicker(
                query,
                preparedQuery,
                session,
                self,
                resourceGroup,
                queryType,
                transactionControl,
                transactionManager,
                accessControl,
                executor,
                Ticker.systemTicker(),
                metadata,
                warningCollector);
    }

    static QueryStateMachine beginWithTicker(
            String query,
            Optional<String> preparedQuery,
            Session session,
            URI self,
            ResourceGroupId resourceGroup,
            Optional<QueryType> queryType,
            boolean transactionControl,
            TransactionManager transactionManager,
            AccessControl accessControl,
            Executor executor,
            Ticker ticker,
            Metadata metadata,
            WarningCollector warningCollector)
    {
        // If there is not an existing transaction, begin an auto commit transaction
        if (!session.getTransactionId().isPresent() && !transactionControl) {
            // TODO: make autocommit isolation level a session parameter
            TransactionId transactionId = transactionManager.beginTransaction(true);
            session = session.beginTransactionId(transactionId, transactionManager, accessControl);
        }

        QueryStateMachine queryStateMachine = new QueryStateMachine(
                query,
                preparedQuery,
                session,
                self,
                resourceGroup,
                queryType,
                transactionManager,
                executor,
                ticker,
                metadata,
                warningCollector);

        queryStateMachine.addStateChangeListener(newState -> {
            QUERY_STATE_LOG.debug("Query %s is %s", queryStateMachine.getQueryId(), newState);
            // mark finished or failed transaction as inactive
            if (newState.isDone()) {
                queryStateMachine.getSession().getTransactionId().ifPresent(transactionManager::trySetInactive);
            }
        });
        return queryStateMachine;
    }

    public QueryId getQueryId()
    {
        return queryId;
    }

    public Session getSession()
    {
        return session;
    }

    public long getPeakUserMemoryInBytes()
    {
        return peakUserMemory.get();
    }

    public long getPeakTotalMemoryInBytes()
    {
        return peakTotalMemory.get();
    }

    public long getPeakTaskTotalMemory()
    {
        return peakTaskTotalMemory.get();
    }

    public long getPeakTaskUserMemory()
    {
        return peakTaskUserMemory.get();
    }

    public long getPeakNodeTotalMemory()
    {
        return peakNodeTotalMemory.get();
    }

    public int getCurrentRunningTaskCount()
    {
        return currentRunningTaskCount.get();
    }

    public int incrementCurrentRunningTaskCount()
    {
        int runningTaskCount = currentRunningTaskCount.incrementAndGet();
        peakRunningTaskCount.accumulateAndGet(runningTaskCount, Math::max);
        return runningTaskCount;
    }

    public int decrementCurrentRunningTaskCount()
    {
        return currentRunningTaskCount.decrementAndGet();
    }

    public int getPeakRunningTaskCount()
    {
        return peakRunningTaskCount.get();
    }

    public WarningCollector getWarningCollector()
    {
        return warningCollector;
    }

    public void updateMemoryUsage(
            long deltaUserMemoryInBytes,
            long deltaTotalMemoryInBytes,
            long taskUserMemoryInBytes,
            long taskTotalMemoryInBytes,
            long peakNodeTotalMemoryInBytes)
    {
        currentUserMemory.addAndGet(deltaUserMemoryInBytes);
        currentTotalMemory.addAndGet(deltaTotalMemoryInBytes);
        peakUserMemory.updateAndGet(currentPeakValue -> Math.max(currentUserMemory.get(), currentPeakValue));
        peakTotalMemory.updateAndGet(currentPeakValue -> Math.max(currentTotalMemory.get(), currentPeakValue));
        peakTaskUserMemory.accumulateAndGet(taskUserMemoryInBytes, Math::max);
        peakTaskTotalMemory.accumulateAndGet(taskTotalMemoryInBytes, Math::max);
        peakNodeTotalMemory.accumulateAndGet(peakNodeTotalMemoryInBytes, Math::max);
    }

    public BasicQueryInfo getBasicQueryInfo(Optional<BasicStageExecutionStats> rootStage)
    {
        // Query state must be captured first in order to provide a
        // correct view of the query.  For example, building this
        // information, the query could finish, and the task states would
        // never be visible.
        QueryState state = queryState.get();

        BasicStageExecutionStats stageStats = rootStage.orElse(EMPTY_STAGE_STATS);
        BasicQueryStats queryStats = new BasicQueryStats(
                queryStateTimer.getCreateTimeInMillis(),
                getEndTimeInMillis(),
                queryStateTimer.getWaitingForPrerequisitesTime(),
                queryStateTimer.getQueuedTime(),
                queryStateTimer.getElapsedTime(),
                queryStateTimer.getExecutionTime(),
                queryStateTimer.getAnalysisTime(),

                getCurrentRunningTaskCount(),
                getPeakRunningTaskCount(),

                stageStats.getTotalDrivers(),
                stageStats.getQueuedDrivers(),
                stageStats.getRunningDrivers(),
                stageStats.getCompletedDrivers(),

                stageStats.getTotalNewDrivers(),
                stageStats.getQueuedNewDrivers(),
                stageStats.getRunningNewDrivers(),
                stageStats.getCompletedNewDrivers(),

                stageStats.getTotalSplits(),
                stageStats.getQueuedSplits(),
                stageStats.getRunningSplits(),
                stageStats.getCompletedSplits(),

                succinctBytes(stageStats.getRawInputDataSizeInBytes()),
                stageStats.getRawInputPositions(),

                stageStats.getCumulativeUserMemory(),
                stageStats.getCumulativeTotalMemory(),
                succinctBytes(stageStats.getUserMemoryReservationInBytes()),
                succinctBytes(stageStats.getTotalMemoryReservationInBytes()),
                succinctBytes(getPeakUserMemoryInBytes()),
                succinctBytes(getPeakTotalMemoryInBytes()),
                succinctBytes(getPeakTaskTotalMemory()),
                succinctBytes(getPeakNodeTotalMemory()),

                stageStats.getTotalCpuTime(),
                stageStats.getTotalScheduledTime(),

                stageStats.isFullyBlocked(),
                stageStats.getBlockedReasons(),

                succinctBytes(stageStats.getTotalAllocationInBytes()),

                stageStats.getProgressPercentage());

        return new BasicQueryInfo(
                queryId,
                session.toSessionRepresentation(),
                Optional.of(resourceGroup),
                state,
                memoryPool.get().getId(),
                stageStats.isScheduled(),
                self,
                query,
                queryStats,
                failureCause.get(),
                queryType,
                warningCollector.getWarnings(),
                preparedQuery);
    }

    public QueryInfo getQueryInfo(Optional<StageInfo> rootStage)
    {
        // Query state must be captured first in order to provide a
        // correct view of the query.  For example, building this
        // information, the query could finish, and the task states would
        // never be visible.
        QueryState state = queryState.get();

        ExecutionFailureInfo failureCause = null;
        ErrorCode errorCode = null;
        if (state == QueryState.FAILED) {
            failureCause = this.failureCause.get();
            if (failureCause != null) {
                errorCode = failureCause.getErrorCode();
            }
        }

        List<StageInfo> allStages = getAllStages(rootStage);
        boolean finalInfo = state.isDone() && allStages.stream().allMatch(StageInfo::isFinalStageInfo);
        Optional<List<TaskId>> failedTasks;
        // Traversing all tasks is expensive, thus only construct failedTasks list when query finished.
        if (state.isDone()) {
            failedTasks = Optional.of(allStages.stream()
                    .flatMap(stageInfo -> Streams.concat(ImmutableList.of(stageInfo.getLatestAttemptExecutionInfo()).stream(), stageInfo.getPreviousAttemptsExecutionInfos().stream()))
                    .flatMap(execution -> execution.getTasks().stream())
                    .filter(taskInfo -> taskInfo.getTaskStatus().getState() == TaskState.FAILED)
                    .map(TaskInfo::getTaskId)
                    .collect(toImmutableList()));
        }
        else {
            failedTasks = Optional.empty();
        }

        List<StageId> runtimeOptimizedStages = allStages.stream().filter(StageInfo::isRuntimeOptimized).map(StageInfo::getStageId).collect(toImmutableList());
        QueryStats queryStats = getQueryStats(rootStage, allStages);
        return new QueryInfo(
                queryId,
                session.toSessionRepresentation(),
                state,
                memoryPool.get().getId(),
                queryStats.isScheduled(),
                self,
                outputManager.getQueryOutputInfo().map(QueryOutputInfo::getColumnNames).orElse(ImmutableList.of()),
                query,
                expandedQuery.get(),
                preparedQuery,
                queryStats,
                Optional.ofNullable(setCatalog.get()),
                Optional.ofNullable(setSchema.get()),
                setSessionProperties,
                resetSessionProperties,
                setRoles,
                addedPreparedStatements,
                deallocatedPreparedStatements,
                Optional.ofNullable(startedTransactionId.get()),
                clearTransactionId.get(),
                updateInfo.get(),
                rootStage,
                failureCause,
                errorCode,
                warningCollector.getWarnings(),
                inputs.get(),
                output.get(),
                finalInfo,
                Optional.of(resourceGroup),
                queryType,
                failedTasks,
                runtimeOptimizedStages.isEmpty() ? Optional.empty() : Optional.of(runtimeOptimizedStages),
                addedSessionFunctions,
                removedSessionFunctions,
                Optional.ofNullable(planStatsAndCosts.get()).orElseGet(StatsAndCosts::empty),
                session.getOptimizerInformationCollector().getOptimizationInfo(),
                session.getCteInformationCollector().getCTEInformationList(),
                scalarFunctions.get(),
                aggregateFunctions.get(),
                windowFunctions.get(),
                Optional.ofNullable(planCanonicalInfo.get()).orElseGet(ImmutableList::of),
                Optional.ofNullable(planIdNodeMap.get()).orElseGet(ImmutableMap::of),
                Optional.empty());
    }

    private QueryStats getQueryStats(Optional<StageInfo> rootStage, List<StageInfo> allStages)
    {
        return QueryStats.create(
                queryStateTimer,
                rootStage,
                allStages,
                getPeakRunningTaskCount(),
                getPeakUserMemoryInBytes(),
                getPeakTotalMemoryInBytes(),
                getPeakTaskUserMemory(),
                getPeakTaskTotalMemory(),
                getPeakNodeTotalMemory(),
                session.getRuntimeStats());
    }

    public VersionedMemoryPoolId getMemoryPool()
    {
        return memoryPool.get();
    }

    public void setMemoryPool(VersionedMemoryPoolId memoryPool)
    {
        this.memoryPool.set(requireNonNull(memoryPool, "memoryPool is null"));
    }

    public void addOutputInfoListener(Consumer<QueryOutputInfo> listener)
    {
        outputManager.addOutputInfoListener(listener);
    }

    public void setColumns(List<String> columnNames, List<Type> columnTypes)
    {
        outputManager.setColumns(columnNames, columnTypes);
    }

    public void updateOutputLocations(Map<URI, TaskId> newExchangeLocations, boolean noMoreExchangeLocations)
    {
        outputManager.updateOutputLocations(newExchangeLocations, noMoreExchangeLocations);
    }

    public void setInputs(List<Input> inputs)
    {
        requireNonNull(inputs, "inputs is null");
        this.inputs.set(ImmutableSet.copyOf(inputs));
    }

    public void setPlanStatsAndCosts(StatsAndCosts statsAndCosts)
    {
        requireNonNull(statsAndCosts, "statsAndCosts is null");
        this.planStatsAndCosts.set(statsAndCosts);
    }

    public void setPlanIdNodeMap(Map<PlanNodeId, PlanNode> planIdNodeMap)
    {
        requireNonNull(planIdNodeMap, "planIdNodeMap is null");
        this.planIdNodeMap.set(ImmutableMap.copyOf(planIdNodeMap));
    }

    public void setPlanCanonicalInfo(List<CanonicalPlanWithInfo> planCanonicalInfo)
    {
        requireNonNull(planCanonicalInfo, "planCanonicalInfo is null");
        this.planCanonicalInfo.set(planCanonicalInfo);
    }

    public void setOutput(Optional<Output> output)
    {
        requireNonNull(output, "output is null");
        this.output.set(output);
    }

    public void setScalarFunctions(Set<String> scalarFunctions)
    {
        requireNonNull(scalarFunctions, "scalarFunctions is null");
        this.scalarFunctions.set(ImmutableSet.copyOf(scalarFunctions));
    }

    public void setAggregateFunctions(Set<String> aggregateFunctions)
    {
        requireNonNull(aggregateFunctions, "aggregateFunctions is null");
        this.aggregateFunctions.set(ImmutableSet.copyOf(aggregateFunctions));
    }

    public void setWindowFunctions(Set<String> windowFunctions)
    {
        requireNonNull(windowFunctions, "windowFunctions is null");
        this.windowFunctions.set(ImmutableSet.copyOf(windowFunctions));
    }

    private void addSerializedCommitOutputToOutput(ConnectorCommitHandle commitHandle)
    {
        if (!output.get().isPresent()) {
            return;
        }
        Output outputInfo = output.get().get();
        SchemaTableName table = new SchemaTableName(outputInfo.getSchema(), outputInfo.getTable());
        output.set(Optional.of(new Output(
                outputInfo.getConnectorId(),
                outputInfo.getSchema(),
                outputInfo.getTable(),
                commitHandle.getSerializedCommitOutputForWrite(table),
                outputInfo.getColumns())));
    }

    private void addSerializedCommitOutputToInputs(List<?> commitHandles)
    {
        ImmutableSet.Builder<Input> builder = ImmutableSet.builder();

        for (Input input : inputs.get()) {
            builder.add(attachSerializedCommitOutput(input, commitHandles));
        }

        inputs.set(builder.build());
    }

    private Input attachSerializedCommitOutput(Input input, List<?> commitHandles)
    {
        SchemaTableName table = new SchemaTableName(input.getSchema(), input.getTable());
        for (Object handle : commitHandles) {
            if (!(handle instanceof ConnectorCommitHandle)) {
                throw new PrestoException(INVALID_ARGUMENTS, "Type ConnectorCommitHandle is expected");
            }

            ConnectorCommitHandle commitHandle = (ConnectorCommitHandle) handle;
            if (commitHandle.hasCommitOutput(table)) {
                return new Input(
                        input.getConnectorId(),
                        input.getSchema(),
                        input.getTable(),
                        input.getConnectorInfo(),
                        input.getColumns(),
                        input.getStatistics(),
                        commitHandle.getSerializedCommitOutputForRead(table));
            }
        }
        return input;
    }

    public Map<String, String> getSetSessionProperties()
    {
        return setSessionProperties;
    }

    public void setSetCatalog(String catalog)
    {
        setCatalog.set(requireNonNull(catalog, "catalog is null"));
    }

    public void setSetSchema(String schema)
    {
        setSchema.set(requireNonNull(schema, "schema is null"));
    }

    public void addSetSessionProperties(String key, String value)
    {
        setSessionProperties.put(requireNonNull(key, "key is null"), requireNonNull(value, "value is null"));
    }

    public void addSetRole(String catalog, SelectedRole role)
    {
        setRoles.put(requireNonNull(catalog, "catalog is null"), requireNonNull(role, "role is null"));
    }

    public Set<String> getResetSessionProperties()
    {
        return resetSessionProperties;
    }

    public void addResetSessionProperties(String name)
    {
        resetSessionProperties.add(requireNonNull(name, "name is null"));
    }

    public Map<String, String> getAddedPreparedStatements()
    {
        return addedPreparedStatements;
    }

    public Set<String> getDeallocatedPreparedStatements()
    {
        return deallocatedPreparedStatements;
    }

    public Map<SqlFunctionId, SqlInvokedFunction> getAddedSessionFunctions()
    {
        return addedSessionFunctions;
    }

    public Set<SqlFunctionId> getRemovedSessionFunctions()
    {
        return removedSessionFunctions;
    }

    public void addPreparedStatement(String key, String value)
    {
        requireNonNull(key, "key is null");
        requireNonNull(value, "value is null");

        addedPreparedStatements.put(key, value);
    }

    public void removePreparedStatement(String key)
    {
        requireNonNull(key, "key is null");

        if (!session.getPreparedStatements().containsKey(key)) {
            throw new PrestoException(NOT_FOUND, "Prepared statement not found: " + key);
        }
        deallocatedPreparedStatements.add(key);
    }

    public void addSessionFunction(SqlFunctionId signature, SqlInvokedFunction function)
    {
        requireNonNull(signature, "signature is null");
        requireNonNull(function, "function is null");

        if (session.getSessionFunctions().containsKey(signature) || addedSessionFunctions.putIfAbsent(signature, function) != null) {
            throw new PrestoException(ALREADY_EXISTS, format("Session function %s has already been defined", signature));
        }
    }

    public void removeSessionFunction(SqlFunctionId signature, boolean suppressNotFoundException)
    {
        requireNonNull(signature, "signature is null");

        if (!session.getSessionFunctions().containsKey(signature)) {
            if (!suppressNotFoundException) {
                throw new PrestoException(NOT_FOUND, format("Session function %s not found", signature.getFunctionName()));
            }
        }
        else {
            removedSessionFunctions.add(signature);
        }
    }

    public void setStartedTransactionId(TransactionId startedTransactionId)
    {
        checkArgument(!clearTransactionId.get(), "Cannot start and clear transaction ID in the same request");
        this.startedTransactionId.set(startedTransactionId);
    }

    public void clearTransactionId()
    {
        checkArgument(startedTransactionId.get() == null, "Cannot start and clear transaction ID in the same request");
        clearTransactionId.set(true);
    }

    public void setUpdateInfo(UpdateInfo updateInfo)
    {
        this.updateInfo.set(updateInfo);
    }

    public void setExpandedQuery(Optional<String> expandedQuery)
    {
        this.expandedQuery.set(expandedQuery);
    }

    public QueryState getQueryState()
    {
        return queryState.get();
    }

    public boolean isDone()
    {
        return queryState.get().isDone();
    }

    public boolean transitionToQueued()
    {
        queryStateTimer.beginQueued();
        return queryState.setIf(QUEUED, currentState -> currentState.ordinal() < QUEUED.ordinal());
    }

    public boolean transitionToWaitingForResources()
    {
        queryStateTimer.beginWaitingForResources();
        return queryState.setIf(WAITING_FOR_RESOURCES, currentState -> currentState.ordinal() < WAITING_FOR_RESOURCES.ordinal());
    }

    public void beginSemanticAnalyzing()
    {
        queryStateTimer.beginSemanticAnalyzing();
    }

    public void beginColumnAccessPermissionChecking()
    {
        queryStateTimer.beginColumnAccessPermissionChecking();
    }

    public void endColumnAccessPermissionChecking()
    {
        queryStateTimer.endColumnAccessPermissionChecking();
    }

    public boolean transitionToDispatching()
    {
        queryStateTimer.beginDispatching();
        return queryState.setIf(DISPATCHING, currentState -> currentState.ordinal() < DISPATCHING.ordinal());
    }

    public boolean transitionToPlanning()
    {
        queryStateTimer.beginPlanning();
        return queryState.setIf(PLANNING, currentState -> currentState.ordinal() < PLANNING.ordinal());
    }

    public boolean transitionToStarting()
    {
        queryStateTimer.beginStarting();
        return queryState.setIf(STARTING, currentState -> currentState.ordinal() < STARTING.ordinal());
    }

    public boolean transitionToRunning()
    {
        queryStateTimer.beginRunning();
        return queryState.setIf(RUNNING, currentState -> currentState.ordinal() < RUNNING.ordinal());
    }

    public boolean transitionToFinishing()
    {
        queryStateTimer.beginFinishing();

        if (!queryState.setIf(FINISHING, currentState -> currentState != FINISHING && !currentState.isDone())) {
            return false;
        }

        Optional<TransactionInfo> transaction = session.getTransactionId()
                .flatMap(transactionManager::getOptionalTransactionInfo);

        if (transaction.isPresent() && transaction.get().isAutoCommitContext()) {
            ListenableFuture<?> commitFuture = transactionManager.asyncCommit(transaction.get().getTransactionId());
            Futures.addCallback(commitFuture, new FutureCallback<Object>()
            {
                @Override
                public void onSuccess(@Nullable Object result)
                {
                    transitionToFinished();
                    processConnectorCommitHandle(result);
                }

                @Override
                public void onFailure(Throwable throwable)
                {
                    transitionToFailed(throwable, currentState -> !currentState.isDone());
                }
            }, directExecutor());
        }
        else {
            transitionToFinished();
        }
        return true;
    }

    // TODO: Simplify the commit logic of the transaction manager.
    private void processConnectorCommitHandle(Object result)
    {
        // For read-only transactions, transaction manager returns a list of commit handles.
        // No need to handle Output here since they are read-only transactions.
        if (result instanceof List) {
            addSerializedCommitOutputToInputs((List<?>) result);
        }

        // For transactions containing write operation, the transaction manager returns a single commit handle.
        if (result instanceof ConnectorCommitHandle) {
            addSerializedCommitOutputToOutput((ConnectorCommitHandle) result);
            addSerializedCommitOutputToInputs(ImmutableList.of(result));
        }
    }

    private void transitionToFinished()
    {
        cleanupQueryQuietly();
        queryStateTimer.endQuery();

        queryState.setIf(FINISHED, currentState -> !currentState.isDone());
    }

    public boolean transitionToFailed(Throwable throwable)
    {
        // When the state enters FINISHING, the only thing remaining is to commit
        // the transaction. It should only be failed if the transaction commit fails.
        return transitionToFailed(throwable, currentState -> currentState != FINISHING && !currentState.isDone());
    }

    private boolean transitionToFailed(Throwable throwable, Predicate<QueryState> predicate)
    {
        QueryState currentState = queryState.get();
        if (!predicate.test(currentState)) {
            QUERY_STATE_LOG.debug(throwable, "Failure is ignored as the query %s is the %s state, ", queryId, currentState);
            return false;
        }

        cleanupQueryQuietly();
        queryStateTimer.endQuery();

        // NOTE: The failure cause must be set before triggering the state change, so
        // listeners can observe the exception. This is safe because the failure cause
        // can only be observed if the transition to FAILED is successful.
        requireNonNull(throwable, "throwable is null");
        failureCause.compareAndSet(null, toFailure(throwable));

        boolean failed = queryState.setIf(QueryState.FAILED, predicate);
        if (failed) {
            QUERY_STATE_LOG.debug(throwable, "Query %s failed", queryId);
            // if the transaction is already gone, do nothing
            session.getTransactionId().flatMap(transactionManager::getOptionalTransactionInfo).ifPresent(transaction -> {
                if (transaction.isAutoCommitContext()) {
                    transactionManager.asyncAbort(transaction.getTransactionId());
                }
                else {
                    transactionManager.fail(transaction.getTransactionId());
                }
            });
        }
        else {
            QUERY_STATE_LOG.debug(throwable, "Failure after query %s finished", queryId);
        }

        return failed;
    }

    public boolean transitionToCanceled()
    {
        cleanupQueryQuietly();
        queryStateTimer.endQuery();

        // NOTE: The failure cause must be set before triggering the state change, so
        // listeners can observe the exception. This is safe because the failure cause
        // can only be observed if the transition to FAILED is successful.
        failureCause.compareAndSet(null, toFailure(new PrestoException(USER_CANCELED, "Query was canceled")));

        boolean canceled = queryState.setIf(QueryState.FAILED, currentState -> !currentState.isDone());
        if (canceled) {
            // if the transaction is already gone, do nothing
            session.getTransactionId().flatMap(transactionManager::getOptionalTransactionInfo).ifPresent(transaction -> {
                if (transaction.isAutoCommitContext()) {
                    transactionManager.asyncAbort(transaction.getTransactionId());
                }
                else {
                    transactionManager.fail(transaction.getTransactionId());
                }
            });
        }

        return canceled;
    }

    private void cleanupQueryQuietly()
    {
        try {
            metadata.cleanupQuery(session);
        }
        catch (Throwable t) {
            QUERY_STATE_LOG.error("Error cleaning up query: %s", t);
        }
    }

    /**
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor. Additionally, it is
     * possible notifications are observed out of order due to the asynchronous execution.
     */
    public void addStateChangeListener(StateChangeListener<QueryState> stateChangeListener)
    {
        queryState.addStateChangeListener(stateChangeListener);
    }

    /**
     * Add a listener for the final query info.  This notification is guaranteed to be fired only once.
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor.
     */
    public void addQueryInfoStateChangeListener(StateChangeListener<QueryInfo> stateChangeListener)
    {
        AtomicBoolean done = new AtomicBoolean();
        StateChangeListener<Optional<QueryInfo>> fireOnceStateChangeListener = finalQueryInfo -> {
            if (finalQueryInfo.isPresent() && done.compareAndSet(false, true)) {
                stateChangeListener.stateChanged(finalQueryInfo.get());
            }
        };
        finalQueryInfo.addStateChangeListener(fireOnceStateChangeListener);
    }

    public ListenableFuture<QueryState> getStateChange(QueryState currentState)
    {
        return queryState.getStateChange(currentState);
    }

    public void recordHeartbeat()
    {
        queryStateTimer.recordHeartbeat();
    }

    public void beginAnalysis()
    {
        queryStateTimer.beginAnalyzing();
    }

    public void endAnalysis()
    {
        queryStateTimer.endAnalysis();
    }

    public long getCreateTimeInMillis()
    {
        return queryStateTimer.getCreateTimeInMillis();
    }

    public Duration getQueuedTime()
    {
        return queryStateTimer.getQueuedTime();
    }

    public long getExecutionStartTimeInMillis()
    {
        return queryStateTimer.getExecutionStartTimeInMillis();
    }

    public long getLastHeartbeatInMillis()
    {
        return queryStateTimer.getLastHeartbeatInMillis();
    }

    public long getEndTimeInMillis()
    {
        return queryStateTimer.getEndTimeInMillis();
    }

    public Optional<ExecutionFailureInfo> getFailureInfo()
    {
        if (queryState.get() != QueryState.FAILED) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.failureCause.get());
    }

    public Optional<QueryInfo> getFinalQueryInfo()
    {
        return finalQueryInfo.get();
    }

    public QueryInfo updateQueryInfo(Optional<StageInfo> stageInfo)
    {
        QueryInfo queryInfo = getQueryInfo(stageInfo);
        if (queryInfo.isFinalQueryInfo()) {
            finalQueryInfo.compareAndSet(Optional.empty(), Optional.of(queryInfo));
        }
        return queryInfo;
    }

    /**
     * Remove large objects from the query info object graph, e.g : plan, stats, stage summaries, failed attempts
     * Used when pruning expired queries from the state machine
     */
    public void pruneQueryInfoExpired()
    {
        Optional<QueryInfo> finalInfo = finalQueryInfo.get();
        if (!finalInfo.isPresent() || !finalInfo.get().getOutputStage().isPresent()) {
            return;
        }
        QueryInfo queryInfo = finalInfo.get();
        QueryInfo prunedQueryInfo;

        prunedQueryInfo = pruneExpiredQueryInfo(queryInfo, getMemoryPool());
        finalQueryInfo.compareAndSet(finalInfo, Optional.of(prunedQueryInfo));
    }

    /**
     * Remove the largest objects from the query info object graph, e.g : extraneous stats, costs,
     * and histograms to reduce memory utilization
     */
    public void pruneQueryInfoFinished()
    {
        Optional<QueryInfo> finalInfo = finalQueryInfo.get();
        if (!finalInfo.isPresent() || !finalInfo.get().getOutputStage().isPresent()) {
            return;
        }

        QueryInfo queryInfo = finalInfo.get();
        QueryInfo prunedQueryInfo;

        // no longer needed in the session after query finishes
        session.getPlanNodeStatsMap().clear();
        session.getPlanNodeCostMap().clear();
        // inputs contain some statistics which should be cleared
        inputs.getAndUpdate(QueryStateMachine::pruneInputHistograms);
        // query listeners maintain state in their arguments which holds
        // onto plan nodes and statistics. Since finalQueryInfo was
        // already set it should be in a terminal state and be safe to
        // clear the listeners.
        finalQueryInfo.clearEventListeners();
        planStatsAndCosts.getAndUpdate(stats -> Optional.ofNullable(stats)
                .map(QueryStateMachine::pruneHistogramsFromStatsAndCosts)
                .orElse(null));
        prunedQueryInfo = pruneFinishedQueryInfo(queryInfo, inputs.get());
        finalQueryInfo.compareAndSet(finalInfo, Optional.of(prunedQueryInfo));
    }

    private static QueryInfo pruneFinishedQueryInfo(QueryInfo queryInfo, Set<Input> prunedInputs)
    {
        return new QueryInfo(
                queryInfo.getQueryId(),
                queryInfo.getSession(),
                queryInfo.getState(),
                queryInfo.getMemoryPool(),
                queryInfo.isScheduled(),
                queryInfo.getSelf(),
                queryInfo.getFieldNames(),
                queryInfo.getQuery(),
                queryInfo.getExpandedQuery(),
                queryInfo.getPreparedQuery(),
                queryInfo.getQueryStats(),
                queryInfo.getSetCatalog(),
                queryInfo.getSetSchema(),
                queryInfo.getSetSessionProperties(),
                queryInfo.getResetSessionProperties(),
                queryInfo.getSetRoles(),
                queryInfo.getAddedPreparedStatements(),
                queryInfo.getDeallocatedPreparedStatements(),
                queryInfo.getStartedTransactionId(),
                queryInfo.isClearTransactionId(),
                queryInfo.getUpdateInfo(),
                queryInfo.getOutputStage().map(QueryStateMachine::pruneStatsFromStageInfo),
                queryInfo.getFailureInfo(),
                queryInfo.getErrorCode(),
                queryInfo.getWarnings(),
                prunedInputs,
                queryInfo.getOutput(),
                queryInfo.isFinalQueryInfo(),
                queryInfo.getResourceGroupId(),
                queryInfo.getQueryType(),
                queryInfo.getFailedTasks(),
                queryInfo.getRuntimeOptimizedStages(),
                queryInfo.getAddedSessionFunctions(),
                queryInfo.getRemovedSessionFunctions(),
                pruneHistogramsFromStatsAndCosts(queryInfo.getPlanStatsAndCosts()),
                queryInfo.getOptimizerInformation(),
                queryInfo.getCteInformationList(),
                queryInfo.getScalarFunctions(),
                queryInfo.getAggregateFunctions(),
                queryInfo.getWindowFunctions(),
                ImmutableList.of(),
                ImmutableMap.of(),
                queryInfo.getPrestoSparkExecutionContext());
    }

    private static Set<Input> pruneInputHistograms(Set<Input> inputs)
    {
        return inputs.stream().map(input -> new Input(input.getConnectorId(),
                        input.getSchema(),
                        input.getTable(),
                        input.getConnectorInfo(),
                        input.getColumns(),
                        input.getStatistics().map(tableStats -> TableStatistics.buildFrom(tableStats)
                                .setColumnStatistics(ImmutableMap.copyOf(
                                        Maps.transformValues(tableStats.getColumnStatistics(),
                                                columnStats -> ColumnStatistics.buildFrom(columnStats)
                                                        .setHistogram(Optional.empty())
                                                        .build())))
                                .build()),
                        input.getSerializedCommitOutput()))
                .collect(toImmutableSet());
    }

    protected static StatsAndCosts pruneHistogramsFromStatsAndCosts(StatsAndCosts statsAndCosts)
    {
        Map<PlanNodeId, PlanNodeStatsEstimate> newStats = statsAndCosts.getStats()
                .entrySet()
                .stream()
                .collect(toImmutableMap(entry -> entry.getKey(),
                        entry -> PlanNodeStatsEstimate.buildFrom(entry.getValue())
                                .addVariableStatistics(ImmutableMap.copyOf(
                                        Maps.transformValues(
                                                entry.getValue().getVariableStatistics(),
                                                variableStats -> VariableStatsEstimate.buildFrom(variableStats)
                                                        .setHistogram(Optional.empty())
                                                        .build())))
                                .build()));

        return new StatsAndCosts(newStats,
                statsAndCosts.getCosts());
    }

    private static StageInfo pruneStatsFromStageInfo(StageInfo stage)
    {
        return new StageInfo(
                stage.getStageId(),
                stage.getSelf(),
                stage.getPlan().map(plan -> new PlanFragment(
                        plan.getId(),
                        plan.getRoot(),
                        plan.getVariables(),
                        plan.getPartitioning(),
                        plan.getTableScanSchedulingOrder(),
                        plan.getPartitioningScheme(),
                        plan.getStageExecutionDescriptor(),
                        plan.isOutputTableWriterFragment(),
                        plan.getStatsAndCosts().map(QueryStateMachine::pruneHistogramsFromStatsAndCosts),
                        plan.getJsonRepresentation())), // Remove the plan
                stage.getLatestAttemptExecutionInfo(),
                stage.getPreviousAttemptsExecutionInfos(), // Remove failed attempts
                stage.getSubStages().stream()
                        .map(QueryStateMachine::pruneStatsFromStageInfo)
                        .collect(toImmutableList()), // Remove the substages
                stage.isRuntimeOptimized());
    }

    private static QueryInfo pruneExpiredQueryInfo(QueryInfo queryInfo, VersionedMemoryPoolId pool)
    {
        Optional<StageInfo> prunedOutputStage = queryInfo.getOutputStage().map(outputStage -> new StageInfo(
                outputStage.getStageId(),
                outputStage.getSelf(),
                Optional.empty(), // Remove the plan
                pruneStageExecutionInfo(outputStage.getLatestAttemptExecutionInfo()),
                ImmutableList.of(), // Remove failed attempts
                ImmutableList.of(), // Remove the substages
                outputStage.isRuntimeOptimized()));

        return new QueryInfo(
                queryInfo.getQueryId(),
                queryInfo.getSession(),
                queryInfo.getState(),
                pool.getId(),
                queryInfo.isScheduled(),
                queryInfo.getSelf(),
                queryInfo.getFieldNames(),
                queryInfo.getQuery(),
                queryInfo.getExpandedQuery(),
                queryInfo.getPreparedQuery(),
                pruneQueryStats(queryInfo.getQueryStats()),
                queryInfo.getSetCatalog(),
                queryInfo.getSetSchema(),
                queryInfo.getSetSessionProperties(),
                queryInfo.getResetSessionProperties(),
                queryInfo.getSetRoles(),
                queryInfo.getAddedPreparedStatements(),
                queryInfo.getDeallocatedPreparedStatements(),
                queryInfo.getStartedTransactionId(),
                queryInfo.isClearTransactionId(),
                queryInfo.getUpdateInfo(),
                prunedOutputStage,
                queryInfo.getFailureInfo(),
                queryInfo.getErrorCode(),
                queryInfo.getWarnings(),
                queryInfo.getInputs(),
                queryInfo.getOutput(),
                queryInfo.isFinalQueryInfo(),
                queryInfo.getResourceGroupId(),
                queryInfo.getQueryType(),
                queryInfo.getFailedTasks(),
                queryInfo.getRuntimeOptimizedStages(),
                queryInfo.getAddedSessionFunctions(),
                queryInfo.getRemovedSessionFunctions(),
                StatsAndCosts.empty(),
                queryInfo.getOptimizerInformation(),
                queryInfo.getCteInformationList(),
                queryInfo.getScalarFunctions(),
                queryInfo.getAggregateFunctions(),
                queryInfo.getWindowFunctions(),
                ImmutableList.of(),
                ImmutableMap.of(),
                queryInfo.getPrestoSparkExecutionContext());
    }

    private static StageExecutionInfo pruneStageExecutionInfo(StageExecutionInfo info)
    {
        return new StageExecutionInfo(
                info.getState(),
                info.getStats(),
                // Remove the tasks
                ImmutableList.of(),
                info.getFailureCause());
    }

    private static QueryStats pruneQueryStats(QueryStats queryStats)
    {
        return new QueryStats(
                queryStats.getCreateTimeInMillis(),
                queryStats.getExecutionStartTimeInMillis(),
                queryStats.getLastHeartbeatInMillis(),
                queryStats.getEndTimeInMillis(),
                queryStats.getElapsedTime(),
                queryStats.getWaitingForPrerequisitesTime(),
                queryStats.getQueuedTime(),
                queryStats.getResourceWaitingTime(),
                queryStats.getSemanticAnalyzingTime(),
                queryStats.getColumnAccessPermissionCheckingTime(),
                queryStats.getDispatchingTime(),
                queryStats.getExecutionTime(),
                queryStats.getAnalysisTime(),
                queryStats.getTotalPlanningTime(),
                queryStats.getFinishingTime(),
                queryStats.getTotalTasks(),
                queryStats.getRunningTasks(),
                queryStats.getCompletedTasks(),
                queryStats.getPeakRunningTasks(),
                queryStats.getTotalDrivers(),
                queryStats.getQueuedDrivers(),
                queryStats.getRunningDrivers(),
                queryStats.getBlockedDrivers(),
                queryStats.getCompletedDrivers(),
                queryStats.getTotalNewDrivers(),
                queryStats.getQueuedNewDrivers(),
                queryStats.getRunningNewDrivers(),
                queryStats.getCompletedNewDrivers(),
                queryStats.getTotalSplits(),
                queryStats.getQueuedSplits(),
                queryStats.getRunningSplits(),
                queryStats.getCompletedSplits(),
                queryStats.getCumulativeUserMemory(),
                queryStats.getCumulativeTotalMemory(),
                queryStats.getUserMemoryReservation(),
                queryStats.getTotalMemoryReservation(),
                queryStats.getPeakUserMemoryReservation(),
                queryStats.getPeakTotalMemoryReservation(),
                queryStats.getPeakTaskUserMemory(),
                queryStats.getPeakTaskTotalMemory(),
                queryStats.getPeakNodeTotalMemory(),
                queryStats.isScheduled(),
                queryStats.getTotalScheduledTime(),
                queryStats.getTotalCpuTime(),
                queryStats.getRetriedCpuTime(),
                queryStats.getTotalBlockedTime(),
                queryStats.isFullyBlocked(),
                queryStats.getBlockedReasons(),
                queryStats.getTotalAllocation(),
                queryStats.getRawInputDataSize(),
                queryStats.getRawInputPositions(),
                queryStats.getProcessedInputDataSize(),
                queryStats.getProcessedInputPositions(),
                queryStats.getShuffledDataSize(),
                queryStats.getShuffledPositions(),
                queryStats.getOutputDataSize(),
                queryStats.getOutputPositions(),
                queryStats.getWrittenOutputPositions(),
                queryStats.getWrittenOutputLogicalDataSize(),
                queryStats.getWrittenOutputPhysicalDataSize(),
                queryStats.getWrittenIntermediatePhysicalDataSize(),
                queryStats.getStageGcStatistics(),
                ImmutableList.of(), // Remove the operator summaries as OperatorInfo (especially ExchangeClientStatus) can hold onto a large amount of memory
                queryStats.getRuntimeStats());
    }

    public static class QueryOutputManager
    {
        private final Executor executor;

        @GuardedBy("this")
        private final List<Consumer<QueryOutputInfo>> outputInfoListeners = new ArrayList<>();

        @GuardedBy("this")
        private List<String> columnNames;
        @GuardedBy("this")
        private List<Type> columnTypes;
        @GuardedBy("this")
        private final Map<URI, TaskId> exchangeLocations = new LinkedHashMap<>();
        @GuardedBy("this")
        private boolean noMoreExchangeLocations;

        public QueryOutputManager(Executor executor)
        {
            this.executor = requireNonNull(executor, "executor is null");
        }

        public void addOutputInfoListener(Consumer<QueryOutputInfo> listener)
        {
            requireNonNull(listener, "listener is null");

            Optional<QueryOutputInfo> queryOutputInfo;
            synchronized (this) {
                outputInfoListeners.add(listener);
                queryOutputInfo = getQueryOutputInfo();
            }
            queryOutputInfo.ifPresent(info -> executor.execute(() -> listener.accept(info)));
        }

        public void setColumns(List<String> columnNames, List<Type> columnTypes)
        {
            requireNonNull(columnNames, "columnNames is null");
            requireNonNull(columnTypes, "columnTypes is null");
            checkArgument(columnNames.size() == columnTypes.size(), "columnNames and columnTypes must be the same size");

            Optional<QueryOutputInfo> queryOutputInfo;
            List<Consumer<QueryOutputInfo>> outputInfoListeners;
            synchronized (this) {
                checkState(this.columnNames == null && this.columnTypes == null, "output fields already set");
                this.columnNames = ImmutableList.copyOf(columnNames);
                this.columnTypes = ImmutableList.copyOf(columnTypes);

                queryOutputInfo = getQueryOutputInfo();
                outputInfoListeners = ImmutableList.copyOf(this.outputInfoListeners);
            }
            queryOutputInfo.ifPresent(info -> fireStateChanged(info, outputInfoListeners));
        }

        public void updateOutputLocations(Map<URI, TaskId> newExchangeLocations, boolean noMoreExchangeLocations)
        {
            requireNonNull(newExchangeLocations, "newExchangeLocations is null");

            Optional<QueryOutputInfo> queryOutputInfo;
            List<Consumer<QueryOutputInfo>> outputInfoListeners;
            synchronized (this) {
                if (this.noMoreExchangeLocations) {
                    checkArgument(this.exchangeLocations.keySet().containsAll(newExchangeLocations.keySet()), "New locations added after no more locations set");
                    return;
                }

                this.exchangeLocations.putAll(newExchangeLocations);
                this.noMoreExchangeLocations = noMoreExchangeLocations;
                queryOutputInfo = getQueryOutputInfo();
                outputInfoListeners = ImmutableList.copyOf(this.outputInfoListeners);
            }
            queryOutputInfo.ifPresent(info -> fireStateChanged(info, outputInfoListeners));
        }

        private synchronized Optional<QueryOutputInfo> getQueryOutputInfo()
        {
            if (columnNames == null || columnTypes == null) {
                return Optional.empty();
            }
            return Optional.of(new QueryOutputInfo(columnNames, columnTypes, exchangeLocations, noMoreExchangeLocations));
        }

        private void fireStateChanged(QueryOutputInfo queryOutputInfo, List<Consumer<QueryOutputInfo>> outputInfoListeners)
        {
            for (Consumer<QueryOutputInfo> outputInfoListener : outputInfoListeners) {
                executor.execute(() -> outputInfoListener.accept(queryOutputInfo));
            }
        }
    }
}
