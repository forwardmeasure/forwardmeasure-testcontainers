/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.forwardmeasure.testcontainers.cassandra;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A keyspace a {@link CassandraTestContainer} created for one test (class).
 *
 * @param name the unquoted, lower-case keyspace name: a letter, then up to 47 of {@code [a-z0-9_]},
 *     so it needs no quoting in CQL
 */
public record CassandraKeyspace(String name) {

  /** Cassandra's own limit on keyspace names is 48 characters. */
  public static final int MAX_NAME_LENGTH = 48;

  static final Pattern VALID_NAME =
      Pattern.compile("[a-z][a-z0-9_]{0," + (MAX_NAME_LENGTH - 1) + "}");

  public CassandraKeyspace {
    Objects.requireNonNull(name, "name");
    if (!VALID_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid unquoted Cassandra keyspace name: " + name);
    }
  }
}
