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
package com.facebook.presto.plugin.jdbc.mapping.functions;

import com.facebook.presto.plugin.jdbc.mapping.ReadFunction;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface LongReadFunction
        extends ReadFunction
{
    @Override
    default Class<?> getJavaType()
    {
        return long.class;
    }

    long readLong(ResultSet resultSet, int columnIndex)
            throws SQLException;
}
