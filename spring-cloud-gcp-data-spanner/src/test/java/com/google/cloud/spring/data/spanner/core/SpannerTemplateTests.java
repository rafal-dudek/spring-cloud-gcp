/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spring.data.spanner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.ByteArray;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Options;
import com.google.cloud.spanner.Options.ReadOption;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import com.google.cloud.spanner.TransactionRunner.TransactionCallable;
import com.google.cloud.spring.data.spanner.core.admin.CachingComposingSupplier;
import com.google.cloud.spring.data.spanner.core.admin.SpannerSchemaUtils;
import com.google.cloud.spring.data.spanner.core.convert.SpannerEntityProcessor;
import com.google.cloud.spring.data.spanner.core.convert.SpannerWriteConverter;
import com.google.cloud.spring.data.spanner.core.mapping.Column;
import com.google.cloud.spring.data.spanner.core.mapping.Embedded;
import com.google.cloud.spring.data.spanner.core.mapping.Interleaved;
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey;
import com.google.cloud.spring.data.spanner.core.mapping.SpannerMappingContext;
import com.google.cloud.spring.data.spanner.core.mapping.Table;
import com.google.cloud.spring.data.spanner.core.mapping.Where;
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterDeleteEvent;
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterExecuteDmlEvent;
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterQueryEvent;
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterReadEvent;
import com.google.cloud.spring.data.spanner.core.mapping.event.AfterSaveEvent;
import com.google.cloud.spring.data.spanner.core.mapping.event.BeforeDeleteEvent;
import com.google.cloud.spring.data.spanner.core.mapping.event.BeforeExecuteDmlEvent;
import com.google.cloud.spring.data.spanner.core.mapping.event.BeforeSaveEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Tests for the Spanner Template. */
class SpannerTemplateTests {

  private static final Statement DML = Statement.of("update statement");

  private DatabaseClient databaseClient;

  private SpannerMappingContext mappingContext;

  private SpannerEntityProcessor objectMapper;

  private SpannerMutationFactory mutationFactory;

  private ReadContext readContext;

  private SpannerTemplate spannerTemplate;

  private SpannerSchemaUtils schemaUtils;

  @BeforeEach
  void setUp() {
    this.databaseClient = mock(DatabaseClient.class);
    this.mappingContext = new SpannerMappingContext();
    this.objectMapper = mock(SpannerEntityProcessor.class);
    this.mutationFactory = mock(SpannerMutationFactory.class);
    this.schemaUtils = new SpannerSchemaUtils(this.mappingContext, this.objectMapper, true);
    this.readContext = mock(ReadContext.class);
    when(this.databaseClient.singleUse()).thenReturn(this.readContext);
    when(this.objectMapper.getWriteConverter()).thenReturn(new SpannerWriteConverter());
    this.spannerTemplate =
        new SpannerTemplate(
            () -> this.databaseClient,
            this.mappingContext,
            this.objectMapper,
            this.mutationFactory,
            this.schemaUtils);
  }

  @Test
  void multipleDatabaseClientTest() {
    DatabaseClient databaseClient1 = mock(DatabaseClient.class);
    DatabaseClient databaseClient2 = mock(DatabaseClient.class);

    when(databaseClient1.singleUse()).thenReturn(this.readContext);
    when(databaseClient2.singleUse()).thenReturn(this.readContext);

    AtomicInteger currentClient = new AtomicInteger(1);

    Supplier<Integer> regionProvider = currentClient::getAndIncrement;

    // this client selector will alternate between the two clients
    Supplier<DatabaseClient> clientProvider =
        new CachingComposingSupplier<>(
            regionProvider, u -> u % 2 == 1 ? databaseClient1 : databaseClient2);

    SpannerTemplate template =
        new SpannerTemplate(
            clientProvider,
            this.mappingContext,
            this.objectMapper,
            this.mutationFactory,
            this.schemaUtils);

    // this first read should use the first client
    template.read(TestEntity.class, Key.of("key"));
    verify(databaseClient1, times(1)).singleUse();
    verify(databaseClient2, times(0)).singleUse();

    // this second read should use the second client
    template.read(TestEntity.class, Key.of("key"));
    verify(databaseClient1, times(1)).singleUse();
    verify(databaseClient2, times(1)).singleUse();

    // this third read should use the first client again
    template.read(TestEntity.class, Key.of("key"));
    verify(databaseClient1, times(2)).singleUse();
    verify(databaseClient2, times(1)).singleUse();
  }

  @Test
  void executeDmlTest() {
    TransactionContext context = mock(TransactionContext.class);
    TransactionRunner transactionRunner = mock(TransactionRunner.class);
    when(this.databaseClient.readWriteTransaction()).thenReturn(transactionRunner);

    when(transactionRunner.run(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallable transactionCallable = invocation.getArgument(0);
              return transactionCallable.run(context);
            });

    when(context.executeUpdate(DML)).thenReturn(333L);

    verifyBeforeAndAfterEvents(
        new BeforeExecuteDmlEvent(DML),
        new AfterExecuteDmlEvent(DML, 333L),
        () -> this.spannerTemplate.executeDmlStatement(DML),
        x -> {});
    verify(context, times(1)).executeUpdate(DML);
  }

  @Test
  void executePartitionedDmlTest() {
    when(this.databaseClient.executePartitionedUpdate(DML)).thenReturn(333L);
    verifyBeforeAndAfterEvents(
        new BeforeExecuteDmlEvent(DML),
        new AfterExecuteDmlEvent(DML, 333L),
        () -> this.spannerTemplate.executePartitionedDmlStatement(DML),
        x -> x.verify(this.databaseClient, times(1)).executePartitionedUpdate(DML));
  }

  @Test
  void readWriteTransactionTest() {

    TransactionRunner transactionRunner = mock(TransactionRunner.class);
    when(this.databaseClient.readWriteTransaction()).thenReturn(transactionRunner);

    TransactionContext transactionContext = mock(TransactionContext.class);

    when(transactionRunner.run(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallable transactionCallable = invocation.getArgument(0);
              return transactionCallable.run(transactionContext);
            });

    TestEntity t = new TestEntity();

    String finalResult =
        this.spannerTemplate.performReadWriteTransaction(
            spannerTemplate -> {
              List<TestEntity> items = spannerTemplate.readAll(TestEntity.class);
              spannerTemplate.update(t);
              spannerTemplate.executeDmlStatement(DML);
              return "all done";
            });

    assertThat(finalResult).isEqualTo("all done");
    verify(transactionContext, times(1)).buffer((List<Mutation>) any());
    verify(transactionContext, times(1)).read(eq("custom_test_table"), any(), any());
    verify(transactionContext, times(1)).executeUpdate(DML);
  }

  @Test
  void existsByIdTest() {
    ResultSet results = mock(ResultSet.class);
    when(results.next()).thenReturn(true);

    when(this.readContext.read(any(), any(), any(), any())).thenReturn(results);
    when(this.databaseClient.singleUse(any())).thenReturn(this.readContext);

    Key key = Key.of("key");
    KeySet keySet = KeySet.singleKey(key);
    assertThat(this.spannerTemplate.existsById(TestEntity.class, key)).isTrue();

    verify(this.databaseClient, times(1)).singleUse();
    verify(this.readContext, times(1))
        .read("custom_test_table", keySet, Collections.singleton("id"));
  }

  @Test
  void existsByIdEmbeddedKeyTest() {
    ResultSet results = mock(ResultSet.class);
    when(results.next()).thenReturn(false);

    when(this.readContext.read(any(), any(), any(), any())).thenReturn(results);
    when(this.databaseClient.singleUse(any())).thenReturn(this.readContext);

    Key key = Key.of("key");
    KeySet keySet = KeySet.singleKey(key);
    assertThat(this.spannerTemplate.existsById(TestEntityEmbeddedPrimaryKey.class, key)).isFalse();

    verify(this.databaseClient, times(1)).singleUse();
    verify(this.readContext, times(1))
        .read("test_table_embedded_pk", keySet, Collections.singleton("stringId"));
  }

  @Test
  void readOnlyTransactionTest() {

    ReadOnlyTransaction readOnlyTransaction = mock(ReadOnlyTransaction.class);
    when(this.databaseClient.readOnlyTransaction(
            TimestampBound.ofMinReadTimestamp(Timestamp.ofTimeMicroseconds(333))))
        .thenReturn(readOnlyTransaction);

    String finalResult =
        this.spannerTemplate.performReadOnlyTransaction(
            spannerOperations -> {
              List<TestEntity> items = spannerOperations.readAll(TestEntity.class);
              TestEntity item = spannerOperations.read(TestEntity.class, Key.of("key"));
              return "all done";
            },
            new SpannerReadOptions()
                .setTimestampBound(
                    TimestampBound.ofMinReadTimestamp(Timestamp.ofTimeMicroseconds(333L))));

    assertThat(finalResult).isEqualTo("all done");
    verify(readOnlyTransaction, times(2)).read(eq("custom_test_table"), any(), any());
  }


  @Test
  void readOnlyTransactionDmlTest() {

    ReadOnlyTransaction readOnlyTransaction = mock(ReadOnlyTransaction.class);
    when(this.databaseClient.readOnlyTransaction(
            // exact staleness is expected.
            TimestampBound.ofReadTimestamp(Timestamp.ofTimeMicroseconds(333))))
        .thenReturn(readOnlyTransaction);

    SpannerReadOptions testSpannerReadOptions = new SpannerReadOptions().setTimestamp(Timestamp.ofTimeMicroseconds(333));
    Function<SpannerTemplate, Void> testSpannerOperations = spannerOperations -> {
      spannerOperations.executeDmlStatement(Statement.of("fail"));
      return null;
    };

    assertThatThrownBy(() -> this.spannerTemplate.performReadOnlyTransaction(
            testSpannerOperations, testSpannerReadOptions))
            .hasMessage("A read-only transaction template cannot execute DML.");
  }

  @Test
  void readOnlyTransactionPartitionedDmlTest() {

    ReadOnlyTransaction readOnlyTransaction = mock(ReadOnlyTransaction.class);
    when(this.databaseClient.readOnlyTransaction(
            TimestampBound.ofReadTimestamp(Timestamp.ofTimeMicroseconds(333))))
        .thenReturn(readOnlyTransaction);

    SpannerReadOptions testSpannerReadOptions = new SpannerReadOptions().setTimestamp(Timestamp.ofTimeMicroseconds(333));
    Function<SpannerTemplate, Void> testSpannerOperations = spannerOperations -> {
      spannerOperations.executePartitionedDmlStatement(Statement.of("fail"));
      return null;
    };

    assertThatThrownBy(() -> this.spannerTemplate.performReadOnlyTransaction(
            testSpannerOperations, testSpannerReadOptions))
            .hasMessage("A read-only transaction template cannot execute partitioned DML.");
  }

  @Test
  void readWriteTransactionPartitionedDmlTest() {

    TransactionRunner transactionRunner = mock(TransactionRunner.class);
    when(this.databaseClient.readWriteTransaction()).thenReturn(transactionRunner);

    TransactionContext transactionContext = mock(TransactionContext.class);

    when(transactionRunner.run(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallable transactionCallable = invocation.getArgument(0);
              return transactionCallable.run(transactionContext);
            });
    Function<SpannerTemplate, String> testSpannerOperations = spannerTemplate -> {
      spannerTemplate.executePartitionedDmlStatement(Statement.of("DML statement here"));
      return "all done";
    };

    assertThatThrownBy(() -> this.spannerTemplate.performReadWriteTransaction(testSpannerOperations))
            .hasMessage("A read-write transaction template cannot execute partitioned" + " DML.");
  }

  @Test
  void nullDatabaseClientTest() {

    assertThatThrownBy(() -> new SpannerTemplate(
            null, this.mappingContext, this.objectMapper, this.mutationFactory, this.schemaUtils))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A valid database client for Spanner is required.");
  }

  @Test
  void nullMappingContextTest() {

    assertThatThrownBy(() ->  new SpannerTemplate(
            () -> this.databaseClient, null, this.objectMapper, this.mutationFactory, this.schemaUtils))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A valid mapping context for Spanner is required.");
  }

  @Test
  void nullObjectMapperTest() {

    assertThatThrownBy(() ->  new SpannerTemplate(
            () -> this.databaseClient, this.mappingContext, null, this.mutationFactory, this.schemaUtils))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A valid entity processor for Spanner is required.");
  }

  @Test
  void nullMutationFactoryTest() {

    assertThatThrownBy(() ->  new SpannerTemplate(
            () -> this.databaseClient, this.mappingContext, this.objectMapper, null, this.schemaUtils))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A valid Spanner mutation factory is required.");
  }

  @Test
  void getMappingContextTest() {
    assertThat(this.spannerTemplate.getMappingContext()).isSameAs(this.mappingContext);
  }

  @Test
  void findSingleKeyNullTest() {
    when(this.readContext.read(any(), any(), any())).thenReturn(null);
    Key key = Key.of("key");
    KeySet keys = KeySet.newBuilder().addKey(key).build();
    verifyAfterEvents(
        new AfterReadEvent(Collections.emptyList(), keys, null),
        () -> assertThat(this.spannerTemplate.read(TestEntity.class, key)).isNull(),
        x -> {});
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void queryTest() {
    when(this.readContext.read(any(), any(), any())).thenReturn(null);
    Statement query = Statement.of("test");
    verifyAfterEvents(
        new AfterQueryEvent(Collections.emptyList(), query, null),
        () -> assertThat(this.spannerTemplate.query(TestEntity.class, query, null)).isEmpty(),
        x -> {});
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void queryFuncTest() {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.next()).thenReturn(false);
    Statement query = Statement.of("test");
    when(this.readContext.executeQuery(query)).thenReturn(resultSet);
    verifyAfterEvents(
        new AfterQueryEvent(Collections.emptyList(), query, null),
        () -> assertThat(this.spannerTemplate.query(x -> null, query, null)).isEmpty(),
        x -> {});
  }

  @Test
  void findSingleKeyTest() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    spyTemplate.read(TestEntity.class, Key.of("key"));
    verify(spyTemplate, times(1)).read(TestEntity.class, Key.of("key"), null);
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void findKeySetTest() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    KeySet keys = KeySet.newBuilder().addKey(Key.of("key1")).addKey(Key.of("key2")).build();
    spyTemplate.read(TestEntity.class, keys);
    verify(spyTemplate, times(1)).read(eq(TestEntity.class), same(keys), eq(null));
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void findKeySetTestEagerOptions() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    KeySet keys = KeySet.newBuilder().addKey(Key.of("key1")).addKey(Key.of("key2")).build();
    SpannerReadOptions options = new SpannerReadOptions();
    spyTemplate.read(ParentEntity.class, keys, options);
    verify(spyTemplate, times(1)).read(eq(ParentEntity.class), same(keys), eq(options));
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void findKeySetTestEagerConvertedOptions() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    KeySet keys = KeySet.newBuilder().addKey(Key.of("key1")).addKey(Key.of("key2")).build();
    SpannerReadOptions options =
            new SpannerReadOptions()
                    .addReadOption(mock(Options.ReadAndQueryOption.class))
                    .addReadOption(mock(Options.ReadQueryUpdateTransactionOption.class));
    spyTemplate.read(ParentEntity.class, keys, options);
    verify(spyTemplate).read(eq(ParentEntity.class), same(keys), eq(options));
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void findKeySetTestEager() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    KeySet keys = KeySet.newBuilder().addKey(Key.of("key1")).addKey(Key.of("key2")).build();
    spyTemplate.read(ParentEntity.class, keys);
    Statement statement =
        Statement.newBuilder(
                "SELECT other, id, custom_col, id_2, ARRAY (SELECT AS STRUCT deleted, id3, id, id_2"
                    + " FROM child_test_table WHERE (child_test_table.id = parent_test_table.id AND"
                    + " child_test_table.id_2 = parent_test_table.id_2) AND (deleted = false)) AS"
                    + " childEntities FROM parent_test_table WHERE (id = @tag0) OR (id_2 = @tag1)")
            .bind("tag0")
            .to("key1")
            .bind("tag1")
            .to("key2")
            .build();
    verify(spyTemplate, times(1)).query(eq(ParentEntity.class), eq(statement), any());
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void readAllTestEager() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    spyTemplate.readAll(ParentEntity.class);
    Statement statement =
        Statement.newBuilder(
                "SELECT other, id, custom_col, id_2, ARRAY (SELECT AS STRUCT deleted, id3, id, id_2"
                    + " FROM child_test_table WHERE (child_test_table.id = parent_test_table.id AND"
                    + " child_test_table.id_2 = parent_test_table.id_2) AND (deleted = false)) AS"
                    + " childEntities FROM parent_test_table")
            .build();
    verify(spyTemplate, times(1)).query(eq(ParentEntity.class), eq(statement), any());
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void findMultipleKeysTest() {
    ResultSet results = mock(ResultSet.class);
    ReadOption readOption = mock(ReadOption.class);
    SpannerReadOptions options =
        new SpannerReadOptions()
            .addReadOption(readOption)
            .setTimestampBound(
                TimestampBound.ofMinReadTimestamp(Timestamp.ofTimeMicroseconds(333L)));
    KeySet keySet = KeySet.singleKey(Key.of("key"));
    when(this.readContext.read(any(), any(), any(), any())).thenReturn(results);
    when(this.databaseClient.singleUse(
            TimestampBound.ofMinReadTimestamp(Timestamp.ofTimeMicroseconds(333L))))
        .thenReturn(this.readContext);

    verifyAfterEvents(
        new AfterReadEvent(Collections.emptyList(), keySet, options),
        () -> this.spannerTemplate.read(TestEntity.class, keySet, options),
        x -> {
          verify(this.objectMapper, times(1))
              .mapToList(same(results), eq(TestEntity.class), isNull(), eq(false));
          verify(this.readContext, times(1))
              .read(eq("custom_test_table"), same(keySet), any(), same(readOption));
        });
    verify(this.databaseClient, times(1))
        .singleUse(TimestampBound.ofMinReadTimestamp(Timestamp.ofTimeMicroseconds(333L)));
  }

  @Test
  void findMultipleKeysWithIndexTest() {
    ResultSet results = mock(ResultSet.class);
    ReadOption readOption = mock(ReadOption.class);
    SpannerReadOptions options =
        new SpannerReadOptions().addReadOption(readOption).setIndex("index");
    KeySet keySet = KeySet.singleKey(Key.of("key"));
    when(this.readContext.readUsingIndex(any(), any(), any(), any(), any())).thenReturn(results);
    this.spannerTemplate.read(TestEntity.class, keySet, options);
    verify(this.objectMapper, times(1))
        .mapToList(same(results), eq(TestEntity.class), isNull(), eq(false));
    verify(this.readContext, times(1))
        .readUsingIndex(
            eq("custom_test_table"), eq("index"), same(keySet), any(), same(readOption));
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void findAllTest() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    SpannerReadOptions options = new SpannerReadOptions();
    spyTemplate.readAll(TestEntity.class, options);
    verify(spyTemplate, times(1)).read(eq(TestEntity.class), eq(KeySet.all()), same(options));
    verify(this.databaseClient, times(1)).singleUse();
  }

  @Test
  void insertTest() {
    Mutation mutation = Mutation.newInsertBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    List<Mutation> mutations = Collections.singletonList(mutation);
    when(this.mutationFactory.insert(entity)).thenReturn(mutations);

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(Collections.singletonList(entity), null),
        new AfterSaveEvent(mutations, Collections.singletonList(entity), null),
        () -> this.spannerTemplate.insert(entity),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void insertAllTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    List<Mutation> mutations = Arrays.asList(mutation, mutation, mutation);
    List entities = Arrays.asList(entity, entity, entity);
    when(this.mutationFactory.insert(same(entity))).thenReturn(Collections.singletonList(mutation));

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(entities, null),
        new AfterSaveEvent(mutations, entities, null),
        () -> this.spannerTemplate.insertAll(entities),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void updateTest() {
    Mutation mutation = Mutation.newUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    List<Mutation> mutations = Collections.singletonList(mutation);
    when(this.mutationFactory.update(entity, null)).thenReturn(mutations);

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(Collections.singletonList(entity), null),
        new AfterSaveEvent(mutations, Collections.singletonList(entity), null),
        () -> this.spannerTemplate.update(entity),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void updateAllTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    List<Mutation> mutations = Arrays.asList(mutation, mutation, mutation);
    List entities = Arrays.asList(entity, entity, entity);
    when(this.mutationFactory.update(same(entity), isNull()))
        .thenReturn(Collections.singletonList(mutation));

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(entities, null),
        new AfterSaveEvent(mutations, entities, null),
        () -> this.spannerTemplate.updateAll(entities),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void updateColumnsArrayTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    List<Mutation> mutations = Collections.singletonList(mutation);
    TestEntity entity = new TestEntity();
    Set<String> cols = new HashSet<>(Arrays.asList("a", "b"));
    when(this.mutationFactory.update(same(entity), eq(cols))).thenReturn(mutations);

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(Collections.singletonList(entity), cols),
        new AfterSaveEvent(mutations, Collections.singletonList(entity), cols),
        () -> this.spannerTemplate.update(entity, "a", "b"),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void updateColumnsSetTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    Set<String> cols = new HashSet<>(Arrays.asList("a", "b"));
    List<Mutation> mutations = Collections.singletonList(mutation);
    when(this.mutationFactory.update(same(entity), eq(cols))).thenReturn(mutations);

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(Collections.singletonList(entity), cols),
        new AfterSaveEvent(mutations, Collections.singletonList(entity), cols),
        () -> this.spannerTemplate.update(entity, cols),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void upsertTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    List<Mutation> mutations = Collections.singletonList(mutation);
    when(this.mutationFactory.upsert(same(entity), isNull())).thenReturn(mutations);

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(Collections.singletonList(entity), null),
        new AfterSaveEvent(mutations, Collections.singletonList(entity), null),
        () -> this.spannerTemplate.upsert(entity),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void upsertAllTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    List<Mutation> mutations = Arrays.asList(mutation, mutation, mutation);
    List entities = Arrays.asList(entity, entity, entity);
    when(this.mutationFactory.upsert(same(entity), isNull()))
        .thenReturn(Collections.singletonList(mutation));

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(entities, null),
        new AfterSaveEvent(mutations, entities, null),
        () -> this.spannerTemplate.upsertAll(entities),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void upsertColumnsArrayTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    Set<String> cols = new HashSet<>(Arrays.asList("a", "b"));
    List<Mutation> mutations = Collections.singletonList(mutation);
    when(this.mutationFactory.upsert(same(entity), eq(cols)))
        .thenReturn(Collections.singletonList(mutation));

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(Collections.singletonList(entity), cols),
        new AfterSaveEvent(mutations, Collections.singletonList(entity), cols),
        () -> this.spannerTemplate.upsert(entity, "a", "b"),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void upsertColumnsSetTest() {
    Mutation mutation = Mutation.newInsertOrUpdateBuilder("custom_test_table").build();
    TestEntity entity = new TestEntity();
    List<Mutation> mutations = Collections.singletonList(mutation);
    Set<String> cols = new HashSet<>(Arrays.asList("a", "b"));
    when(this.mutationFactory.upsert(same(entity), eq(cols)))
        .thenReturn(Collections.singletonList(mutation));

    verifyBeforeAndAfterEvents(
        new BeforeSaveEvent(Collections.singletonList(entity), cols),
        new AfterSaveEvent(mutations, Collections.singletonList(entity), cols),
        () -> this.spannerTemplate.upsert(entity, cols),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void deleteByKeyTest() {
    Key key = Key.of("key");
    Mutation mutation = Mutation.delete("custom_test_table", key);
    KeySet keys = KeySet.newBuilder().addKey(key).build();
    List<Mutation> mutations = Collections.singletonList(mutation);
    when(this.mutationFactory.delete(eq(TestEntity.class), same(key))).thenReturn(mutation);

    verifyBeforeAndAfterEvents(
        new BeforeDeleteEvent(mutations, null, keys, TestEntity.class),
        new AfterDeleteEvent(mutations, null, keys, TestEntity.class),
        () -> this.spannerTemplate.delete(TestEntity.class, key),
        x -> x.verify(this.databaseClient, times(1)).write(Collections.singletonList(mutation)));
  }

  @Test
  void deleteObjectTest() {
    Mutation mutation = Mutation.delete("custom_test_table", Key.of("key"));
    List<Mutation> mutations = Collections.singletonList(mutation);
    TestEntity entity = new TestEntity();
    when(this.mutationFactory.delete(entity)).thenReturn(mutation);

    verifyBeforeAndAfterEvents(
        new BeforeDeleteEvent(mutations, Collections.singletonList(entity), null, null),
        new AfterDeleteEvent(mutations, Collections.singletonList(entity), null, null),
        () -> this.spannerTemplate.delete(entity),
        x -> x.verify(this.databaseClient, times(1)).write(Collections.singletonList(mutation)));
  }

  @Test
  void deleteAllObjectTest() {
    Mutation mutation = Mutation.delete("custom_test_table", Key.of("key"));
    TestEntity entity = new TestEntity();
    List entities = Arrays.asList(entity, entity, entity);
    List<Mutation> mutations = Arrays.asList(mutation, mutation, mutation);
    when(this.mutationFactory.delete(entity)).thenReturn(mutation);

    verifyBeforeAndAfterEvents(
        new BeforeDeleteEvent(mutations, entities, null, null),
        new AfterDeleteEvent(mutations, entities, null, null),
        () -> this.spannerTemplate.deleteAll(Arrays.asList(entity, entity, entity)),
        x -> x.verify(this.databaseClient, times(1)).write(mutations));
  }

  @Test
  void deleteKeysTest() {
    KeySet keys = KeySet.newBuilder().addKey(Key.of("key1")).addKey(Key.of("key2")).build();
    Mutation mutation = Mutation.delete("custom_test_table", keys);
    List<Mutation> mutations = Collections.singletonList(mutation);
    when(this.mutationFactory.delete(eq(TestEntity.class), same(keys))).thenReturn(mutation);

    verifyBeforeAndAfterEvents(
        new BeforeDeleteEvent(mutations, null, keys, TestEntity.class),
        new AfterDeleteEvent(mutations, null, keys, TestEntity.class),
        () -> this.spannerTemplate.delete(TestEntity.class, keys),
        x -> x.verify(this.databaseClient, times(1)).write(Collections.singletonList(mutation)));
  }

  @Test
  void countTest() {
    ResultSet results = mock(ResultSet.class);
    when(this.readContext.executeQuery(Statement.of("SELECT COUNT(*) FROM custom_test_table")))
        .thenReturn(results);
    this.spannerTemplate.count(TestEntity.class);
    verify(results, times(1)).next();
    verify(results, times(1)).getLong(0);
    verify(results, times(1)).close();
  }

  @Test
  void findAllPageableTest() {
    SpannerTemplate spyTemplate = spy(this.spannerTemplate);
    Sort sort = mock(Sort.class);
    Pageable pageable = mock(Pageable.class);

    when(this.databaseClient.singleUse(
            TimestampBound.ofMinReadTimestamp(Timestamp.ofTimeMicroseconds(333L))))
        .thenReturn(this.readContext);

    long offset = 5L;
    int limit = 3;
    SpannerPageableQueryOptions queryOption =
        new SpannerPageableQueryOptions()
            .setOffset(offset)
            .setLimit(limit)
            .setTimestampBound(
                TimestampBound.ofMinReadTimestamp(Timestamp.ofTimeMicroseconds(333L)));

    when(pageable.getOffset()).thenReturn(offset);
    when(pageable.getPageSize()).thenReturn(limit);
    when(pageable.getSort()).thenReturn(sort);

    TestEntity t1 = new TestEntity();
    t1.id = "a";
    TestEntity t2 = new TestEntity();
    t2.id = "b";
    TestEntity t3 = new TestEntity();
    t3.id = "c";

    List<TestEntity> items = new ArrayList<>();
    items.add(t1);
    items.add(t2);
    items.add(t3);

    doReturn(items).when(spyTemplate).queryAll(eq(TestEntity.class), same(queryOption));

    List results = spyTemplate.queryAll(TestEntity.class, queryOption.setSort(pageable.getSort()));
    assertThat(((TestEntity) results.get(0)).id).isEqualTo("a");
    assertThat(((TestEntity) results.get(1)).id).isEqualTo("b");
    assertThat(((TestEntity) results.get(2)).id).isEqualTo("c");
  }

  @Test
  void resolveChildEntityTest() {
    ParentEntity p = new ParentEntity();
    p.id = "key";
    p.id2 = "key2";
    ChildEntity c = new ChildEntity();
    c.id = "key";
    c.id_2 = "key2";
    c.id3 = "key3";
    GrandChildEntity gc = new GrandChildEntity();
    gc.id = "key";
    gc.id_2 = "key2";
    gc.id3 = "key3";
    gc.id4 = "key4";
    when(this.objectMapper.mapToList(any(), eq(ParentEntity.class))).thenReturn(Arrays.asList(p));
    when(this.objectMapper.mapToList(any(), eq(ParentEntity.class), any(), eq(false)))
        .thenReturn(Arrays.asList(p));
    when(this.objectMapper.mapToList(any(), eq(ChildEntity.class), any(), eq(false)))
        .thenReturn(Arrays.asList(c));
    when(this.objectMapper.mapToList(any(), eq(GrandChildEntity.class), any(), eq(false)))
        .thenReturn(Arrays.asList(gc));

    // Verify that only a single event containing only the parent p is published.
    // the recursive resolution of children should NOT give events since there is only one
    // user-call.
    verifyAfterEvents(
        new AfterReadEvent(Collections.singletonList(p), KeySet.all(), null),
        () -> {
          ParentEntity resultWithoutChildren =
              this.spannerTemplate
                  .readAll(
                      ParentEntity.class,
                      new SpannerReadOptions().setIncludeProperties(Collections.singleton("id")))
                  .get(0);
          assertThat(resultWithoutChildren.childEntities).isNull();

          ParentEntity result = this.spannerTemplate.readAll(ParentEntity.class).get(0);
          assertThat(result.childEntities).hasSize(1);
          assertThat(result.childEntities.get(0)).isSameAs(c);
          assertThat(result.childEntities.get(0).childEntities).hasSize(1);
          assertThat(result.childEntities.get(0).childEntities.get(0)).isSameAs(gc);
        },
        x -> {});
  }

  @Test
  void lazyFetchChildrenTest() {
    ChildEntity c = new ChildEntity();
    c.id = "key";
    c.id_2 = "key2";
    c.id3 = "key3";
    GrandChildEntity gc = new GrandChildEntity();
    gc.id = "key";
    gc.id_2 = "key2";
    gc.id3 = "key3";
    gc.id4 = "key4";
    when(this.objectMapper.mapToList(any(), eq(ChildEntity.class), any(), eq(false)))
        .thenReturn(Arrays.asList(c));
    when(this.objectMapper.mapToList(any(), eq(GrandChildEntity.class), any(), eq(false)))
        .thenReturn(Arrays.asList(gc));

    ChildEntity result = this.spannerTemplate.readAll(ChildEntity.class).get(0);

    // expecting only 1 call because the grand-child lazy property hasn't been touched.
    verify(this.objectMapper, times(1)).mapToList(any(), any(), any(), eq(false));

    GrandChildEntity grandChildEntity = result.childEntities.get(0);

    // touching the child causes a second read to be executed.
    verify(this.objectMapper, times(2)).mapToList(any(), any(), any(), eq(false));
  }

  private void verifyEvents(
      ApplicationEvent expectedBefore,
      ApplicationEvent expectedAfter,
      Runnable operation,
      Consumer<InOrder> verifyOperation) {
    ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
    ApplicationEventPublisher mockBeforePublisher = mock(ApplicationEventPublisher.class);
    ApplicationEventPublisher mockAfterPublisher = mock(ApplicationEventPublisher.class);

    InOrder inOrder = Mockito.inOrder(mockBeforePublisher, this.databaseClient, mockAfterPublisher);

    doAnswer(
            invocationOnMock -> {
              ApplicationEvent event = invocationOnMock.getArgument(0);
              if (expectedBefore != null && event.getClass().equals(expectedBefore.getClass())) {
                mockBeforePublisher.publishEvent(event);
              } else if (expectedAfter != null
                  && event.getClass().equals(expectedAfter.getClass())) {
                mockAfterPublisher.publishEvent(event);
              }
              return null;
            })
        .when(mockPublisher)
        .publishEvent(any());

    this.spannerTemplate.setApplicationEventPublisher(mockPublisher);

    operation.run();
    if (expectedBefore != null) {
      inOrder.verify(mockBeforePublisher, times(1)).publishEvent(expectedBefore);
    }
    verifyOperation.accept(inOrder);
    if (expectedAfter != null) {
      inOrder.verify(mockAfterPublisher, times(1)).publishEvent(expectedAfter);
    }
  }

  private void verifyBeforeAndAfterEvents(
      ApplicationEvent expectedBefore,
      ApplicationEvent expectedAfter,
      Runnable operation,
      Consumer<InOrder> verifyOperation) {
    verifyEvents(expectedBefore, expectedAfter, operation, verifyOperation);
  }

  private void verifyAfterEvents(
      ApplicationEvent expectedAfter, Runnable operation, Consumer<InOrder> verifyOperation) {
    verifyEvents(null, expectedAfter, operation, verifyOperation);
  }

  @Table(name = "test_table_embedded_pk")
  private static class TestEntityEmbeddedPrimaryKey {
    @Embedded @PrimaryKey
    EmbeddedPrimaryKey key;
  }

  private static class EmbeddedPrimaryKey {
    @PrimaryKey(keyOrder = 1)
    String stringId;

    @PrimaryKey(keyOrder = 2)
    long id2;
  }

  @Table(name = "custom_test_table")
  private static class TestEntity {
    @PrimaryKey(keyOrder = 1)
    String id;

    @PrimaryKey(keyOrder = 2)
    long id2;

    @Column(name = "custom_col")
    String something;

    @Column(name = "")
    String other;

    ByteArray bytes;

    List<ByteArray> bytesList;

    List<Integer> integerList;

    double[] doubles;
  }

  @Table(name = "parent_test_table")
  private static class ParentEntity {
    @PrimaryKey(keyOrder = 1)
    String id;

    @PrimaryKey(keyOrder = 2)
    @Column(name = "id_2")
    String id2;

    @Column(name = "custom_col")
    String something;

    @Column(name = "")
    String other;

    @Interleaved
    @Where("deleted = false")
    List<ChildEntity> childEntities;
  }

  @Table(name = "child_test_table")
  private static class ChildEntity {
    @PrimaryKey(keyOrder = 1)
    String id;

    @PrimaryKey(keyOrder = 2)
    String id_2;

    @PrimaryKey(keyOrder = 3)
    String id3;

    @Interleaved(lazy = true)
    List<GrandChildEntity> childEntities;

    boolean deleted;
  }

  @Table(name = "grand_child_test_table")
  private static class GrandChildEntity {
    @PrimaryKey(keyOrder = 1)
    String id;

    @PrimaryKey(keyOrder = 2)
    String id_2;

    @PrimaryKey(keyOrder = 3)
    String id3;

    @PrimaryKey(keyOrder = 4)
    String id4;
  }
}
