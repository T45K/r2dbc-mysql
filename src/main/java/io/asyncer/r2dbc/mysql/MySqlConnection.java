/*
 * Copyright 2023 asyncer.io projects
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.asyncer.r2dbc.mysql;

import io.asyncer.r2dbc.mysql.cache.PrepareCache;
import io.asyncer.r2dbc.mysql.cache.QueryCache;
import io.asyncer.r2dbc.mysql.client.Client;
import io.asyncer.r2dbc.mysql.codec.Codecs;
import io.asyncer.r2dbc.mysql.constant.ServerStatuses;
import io.asyncer.r2dbc.mysql.internal.util.StringUtils;
import io.asyncer.r2dbc.mysql.message.client.InitDbMessage;
import io.asyncer.r2dbc.mysql.message.client.PingMessage;
import io.asyncer.r2dbc.mysql.message.server.CompleteMessage;
import io.asyncer.r2dbc.mysql.message.server.ErrorMessage;
import io.asyncer.r2dbc.mysql.message.server.ServerMessage;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Lifecycle;
import io.r2dbc.spi.TransactionDefinition;
import io.r2dbc.spi.ValidationDepth;
import org.jetbrains.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.asyncer.r2dbc.mysql.internal.util.AssertUtils.requireNonEmpty;
import static io.asyncer.r2dbc.mysql.internal.util.AssertUtils.requireNonNull;

/**
 * An implementation of {@link Connection} for connecting to the MySQL database.
 */
public final class MySqlConnection implements Connection, Lifecycle, ConnectionState {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MySqlConnection.class);

    private static final int DEFAULT_LOCK_WAIT_TIMEOUT = 50;

    private static final String ZONE_PREFIX_POSIX = "posix/";

    private static final String ZONE_PREFIX_RIGHT = "right/";

    private static final int PREFIX_LENGTH = 6;

    private static final ServerVersion MARIA_11_1_1 = ServerVersion.create(11, 1, 1, true);

    private static final ServerVersion MYSQL_8_0_3 = ServerVersion.create(8, 0, 3);

    private static final ServerVersion MYSQL_5_7_20 = ServerVersion.create(5, 7, 20);

    private static final ServerVersion MYSQL_8 = ServerVersion.create(8, 0, 0);

    private static final BiConsumer<ServerMessage, SynchronousSink<Boolean>> PING = (message, sink) -> {
        if (message instanceof ErrorMessage) {
            ErrorMessage msg = (ErrorMessage) message;
            logger.debug("Remote validate failed: [{}] [{}] {}", msg.getCode(), msg.getSqlState(),
                msg.getMessage());
            sink.next(false);
            sink.complete();
        } else if (message instanceof CompleteMessage && ((CompleteMessage) message).isDone()) {
            sink.next(true);
            sink.complete();
        } else {
            ReferenceCountUtil.safeRelease(message);
        }
    };

    private static final BiConsumer<ServerMessage, SynchronousSink<Boolean>> INIT_DB = (message, sink) -> {
        if (message instanceof ErrorMessage) {
            ErrorMessage msg = (ErrorMessage) message;
            logger.debug("Use database failed: [{}] [{}] {}", msg.getCode(), msg.getSqlState(),
                msg.getMessage());
            sink.next(false);
            sink.complete();
        } else if (message instanceof CompleteMessage && ((CompleteMessage) message).isDone()) {
            sink.next(true);
            sink.complete();
        } else {
            ReferenceCountUtil.safeRelease(message);
        }
    };

    private static final BiConsumer<ServerMessage, SynchronousSink<Void>> INIT_DB_AFTER = (message, sink) -> {
        if (message instanceof ErrorMessage) {
            sink.error(((ErrorMessage) message).toException());
        } else if (message instanceof CompleteMessage && ((CompleteMessage) message).isDone()) {
            sink.complete();
        } else {
            ReferenceCountUtil.safeRelease(message);
        }
    };

    private final Client client;

    private final Codecs codecs;

    private final boolean batchSupported;

    private final ConnectionContext context;

    private final MySqlConnectionMetadata metadata;

    private volatile IsolationLevel sessionLevel;

    private final QueryCache queryCache;

    private final PrepareCache prepareCache;

    @Nullable
    private final Predicate<String> prepare;

    /**
     * Current isolation level inferred by past statements.
     * <p>
     * Inference rules:
     * <ol><li>In the beginning, it is also {@link #sessionLevel}.</li>
     * <li>After the user calls {@link #setTransactionIsolationLevel(IsolationLevel)}, it will change to
     * the user-specified value.</li>
     * <li>After the end of a transaction (commit or rollback), it will recover to {@link #sessionLevel}.</li>
     * </ol>
     */
    private volatile IsolationLevel currentLevel;

    /**
     * Session lock wait timeout.
     */
    private volatile long lockWaitTimeout;

    /**
     * Current transaction lock wait timeout.
     */
    private volatile long currentLockWaitTimeout;

    MySqlConnection(Client client, ConnectionContext context, Codecs codecs, IsolationLevel level,
        long lockWaitTimeout, QueryCache queryCache, PrepareCache prepareCache, @Nullable String product,
        @Nullable Predicate<String> prepare) {
        this.client = client;
        this.context = context;
        this.sessionLevel = level;
        this.currentLevel = level;
        this.codecs = codecs;
        this.lockWaitTimeout = lockWaitTimeout;
        this.currentLockWaitTimeout = lockWaitTimeout;
        this.queryCache = queryCache;
        this.prepareCache = prepareCache;
        this.metadata = new MySqlConnectionMetadata(context.getServerVersion().toString(), product);
        this.batchSupported = context.getCapability().isMultiStatementsAllowed();
        this.prepare = prepare;

        if (this.batchSupported) {
            logger.debug("Batch is supported by server");
        } else {
            logger.warn("The MySQL server does not support batch, fallback to executing one-by-one");
        }
    }

    @Override
    public Mono<Void> beginTransaction() {
        return beginTransaction(MySqlTransactionDefinition.empty());
    }

    @Override
    public Mono<Void> beginTransaction(TransactionDefinition definition) {
        return Mono.defer(() -> {
            return QueryFlow.beginTransaction(client, this, batchSupported, definition);
        });
    }

    @Override
    public Mono<Void> close() {
        Mono<Void> closer = client.close();

        if (logger.isDebugEnabled()) {
            return closer.doOnSubscribe(s -> logger.debug("Connection closing"))
                .doOnSuccess(ignored -> logger.debug("Connection close succeed"));
        }

        return closer;
    }

    @Override
    public Mono<Void> commitTransaction() {
        return Mono.defer(() -> {
            return QueryFlow.doneTransaction(client, this, true, batchSupported);
        });
    }

    @Override
    public MySqlBatch createBatch() {
        return batchSupported ? new MySqlBatchingBatch(client, codecs, context) :
            new MySqlSyntheticBatch(client, codecs, context);

    }

    @Override
    public Mono<Void> createSavepoint(String name) {
        requireNonEmpty(name, "Savepoint name must not be empty");

        return QueryFlow.createSavepoint(client, this, name, batchSupported);
    }

    @Override
    public MySqlStatement createStatement(String sql) {
        requireNonNull(sql, "sql must not be null");

        Query query = queryCache.get(sql);

        if (query.isSimple()) {
            if (prepare != null && prepare.test(sql)) {
                logger.debug("Create a simple statement provided by prepare query");
                return new PrepareSimpleStatement(client, codecs, context, sql, prepareCache);
            }

            logger.debug("Create a simple statement provided by text query");

            return new TextSimpleStatement(client, codecs, context, sql);
        }

        if (prepare == null) {
            logger.debug("Create a parametrized statement provided by text query");
            return new TextParametrizedStatement(client, codecs, query, context);
        }

        logger.debug("Create a parametrized statement provided by prepare query");

        return new PrepareParametrizedStatement(client, codecs, query, context, prepareCache);
    }

    @Override
    public Mono<Void> postAllocate() {
        return Mono.empty();
    }

    @Override
    public Mono<Void> preRelease() {
        // Rollback if the connection is in transaction.
        return rollbackTransaction();
    }

    @Override
    public Mono<Void> releaseSavepoint(String name) {
        requireNonEmpty(name, "Savepoint name must not be empty");

        return QueryFlow.executeVoid(client, "RELEASE SAVEPOINT " + StringUtils.quoteIdentifier(name));
    }

    @Override
    public Mono<Void> rollbackTransaction() {
        return Mono.defer(() -> QueryFlow.doneTransaction(client, this, false, batchSupported));
    }

    @Override
    public Mono<Void> rollbackTransactionToSavepoint(String name) {
        requireNonEmpty(name, "Savepoint name must not be empty");

        return QueryFlow.executeVoid(client, "ROLLBACK TO SAVEPOINT " + StringUtils.quoteIdentifier(name));
    }

    @Override
    public MySqlConnectionMetadata getMetadata() {
        return metadata;
    }

    /**
     * MySQL does not have any way to query the isolation level of the current transaction, only inferred from
     * past statements, so driver can not make sure the result is right.
     * <p>
     * See <a href="https://bugs.mysql.com/bug.php?id=53341">MySQL Bug 53341</a>
     * <p>
     * {@inheritDoc}
     */
    @Override
    public IsolationLevel getTransactionIsolationLevel() {
        return currentLevel;
    }

    /**
     * Gets session transaction isolation level(Only for testing).
     *
     * @return session transaction isolation level.
     */
    IsolationLevel getSessionTransactionIsolationLevel() {
        return sessionLevel;
    }

    @Override
    public Mono<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
        requireNonNull(isolationLevel, "isolationLevel must not be null");

        // Set subsequent transaction isolation level.
        return QueryFlow.executeVoid(client, "SET SESSION TRANSACTION ISOLATION LEVEL " + isolationLevel.asSql())
            .doOnSuccess(ignored -> {
                this.sessionLevel = isolationLevel;
                if (!this.isInTransaction()) {
                    this.currentLevel = isolationLevel;
                }
            });
    }

    @Override
    public Mono<Boolean> validate(ValidationDepth depth) {
        requireNonNull(depth, "depth must not be null");

        if (depth == ValidationDepth.LOCAL) {
            return Mono.fromSupplier(client::isConnected);
        }

        return Mono.defer(() -> {
            if (!client.isConnected()) {
                return Mono.just(false);
            }

            return client.exchange(PingMessage.INSTANCE, PING)
                .last()
                .onErrorResume(e -> {
                    // `last` maybe emit a NoSuchElementException, exchange maybe emit exception by Netty.
                    // But should NEVER emit any exception, so logging exception and emit false.
                    logger.debug("Remote validate failed", e);
                    return Mono.just(false);
                });
        });
    }

    @Override
    public boolean isAutoCommit() {
        // Within transaction, autocommit remains disabled until end the transaction with COMMIT or ROLLBACK.
        // The autocommit mode then reverts to its previous state.
        return !isInTransaction() && isSessionAutoCommit();
    }

    @Override
    public Mono<Void> setAutoCommit(boolean autoCommit) {
        return Mono.defer(() -> {
            if (autoCommit == isSessionAutoCommit()) {
                return Mono.empty();
            }

            return QueryFlow.executeVoid(client, "SET autocommit=" + (autoCommit ? 1 : 0));
        });
    }

    @Override
    public void setIsolationLevel(IsolationLevel level) {
        this.currentLevel = level;
    }

    @Override
    public long getSessionLockWaitTimeout() {
        return lockWaitTimeout;
    }

    @Override
    public void setCurrentLockWaitTimeout(long timeoutSeconds) {
        this.currentLockWaitTimeout = timeoutSeconds;
    }

    @Override
    public void resetIsolationLevel() {
        this.currentLevel = this.sessionLevel;
    }

    @Override
    public boolean isLockWaitTimeoutChanged() {
        return currentLockWaitTimeout != lockWaitTimeout;
    }

    @Override
    public void resetCurrentLockWaitTimeout() {
        this.currentLockWaitTimeout = this.lockWaitTimeout;
    }

    @Override
    public boolean isInTransaction() {
        return (context.getServerStatuses() & ServerStatuses.IN_TRANSACTION) != 0;
    }

    @Override
    public Mono<Void> setLockWaitTimeout(Duration timeout) {
        requireNonNull(timeout, "timeout must not be null");

        long timeoutSeconds = timeout.getSeconds();
        return QueryFlow.executeVoid(client, "SET innodb_lock_wait_timeout=" + timeoutSeconds)
            .doOnSuccess(ignored -> this.lockWaitTimeout = this.currentLockWaitTimeout = timeoutSeconds);
    }

    @Override
    public Mono<Void> setStatementTimeout(Duration timeout) {
        requireNonNull(timeout, "timeout must not be null");

        // TODO: implement me
        return Mono.empty();
    }

    boolean isSessionAutoCommit() {
        return (context.getServerStatuses() & ServerStatuses.AUTO_COMMIT) != 0;
    }

    /**
     * Initialize a {@link MySqlConnection} after login.
     *
     * @param client       must be logged-in.
     * @param codecs       the {@link Codecs}.
     * @param context      must be initialized.
     * @param database     the database that should be lazy init.
     * @param queryCache   the cache of {@link Query}.
     * @param prepareCache the cache of server-preparing result.
     * @param prepare      judging for prefer use prepare statement to execute simple query.
     * @return a {@link Mono} will emit an initialized {@link MySqlConnection}.
     */
    static Mono<MySqlConnection> init(
        Client client, Codecs codecs, ConnectionContext context, String database,
        QueryCache queryCache, PrepareCache prepareCache,
        @Nullable Predicate<String> prepare
    ) {
        StringBuilder query = new StringBuilder(128)
            .append("SELECT ")
            .append(transactionIsolationColumn(context))
            .append(",@@innodb_lock_wait_timeout AS l,@@version_comment AS v");

        Function<MySqlResult, Publisher<InitData>> handler;

        if (context.shouldSetServerZoneId()) {
            query.append(",@@system_time_zone AS s,@@time_zone AS t");
            handler = MySqlConnection::fullInit;
        } else {
            handler = MySqlConnection::init;
        }

        Mono<MySqlConnection> connection = new TextSimpleStatement(client, codecs, context, query.toString())
            .execute()
            .flatMap(handler)
            .last()
            .map(data -> {
                ZoneId serverZoneId = data.serverZoneId;
                if (serverZoneId != null) {
                    logger.debug("Set server time zone to {} from init query", serverZoneId);
                    context.setServerZoneId(serverZoneId);
                }

                return new MySqlConnection(client, context, codecs, data.level, data.lockWaitTimeout,
                    queryCache, prepareCache, data.product, prepare);
            });

        if (database.isEmpty()) {
            return connection;
        }

        requireNonEmpty(database, "database must not be empty");

        return connection.flatMap(conn -> client.exchange(new InitDbMessage(database), INIT_DB)
            .last()
            .flatMap(success -> {
                if (success) {
                    return Mono.just(conn);
                }

                String sql = "CREATE DATABASE IF NOT EXISTS " + StringUtils.quoteIdentifier(database);

                return QueryFlow.executeVoid(client, sql)
                    .then(client.exchange(new InitDbMessage(database), INIT_DB_AFTER).then(Mono.just(conn)));
            }));
    }

    private static Publisher<InitData> init(MySqlResult r) {
        return r.map((row, meta) -> new InitData(convertIsolationLevel(row.get(0, String.class)),
            convertLockWaitTimeout(row.get(1, Long.class)),
            row.get(2, String.class), null));
    }

    private static Publisher<InitData> fullInit(MySqlResult r) {
        return r.map((row, meta) -> {
            IsolationLevel level = convertIsolationLevel(row.get(0, String.class));
            long lockWaitTimeout = convertLockWaitTimeout(row.get(1, Long.class));
            String product = row.get(2, String.class);
            String systemTimeZone = row.get(3, String.class);
            String timeZone = row.get(4, String.class);
            ZoneId zoneId;

            if (timeZone == null || timeZone.isEmpty() || "SYSTEM".equalsIgnoreCase(timeZone)) {
                if (systemTimeZone == null || systemTimeZone.isEmpty()) {
                    logger.warn("MySQL does not return any timezone, trying to use system default timezone");
                    zoneId = ZoneId.systemDefault();
                } else {
                    zoneId = convertZoneId(systemTimeZone);
                }
            } else {
                zoneId = convertZoneId(timeZone);
            }

            return new InitData(level, lockWaitTimeout, product, zoneId);
        });
    }

    /**
     * Creates a {@link ZoneId} from MySQL timezone result, or fallback to system default timezone if not
     * found.
     *
     * @param id the ID/name of MySQL timezone.
     * @return the {@link ZoneId}.
     */
    private static ZoneId convertZoneId(String id) {
        String realId;

        if (id.startsWith(ZONE_PREFIX_POSIX) || id.startsWith(ZONE_PREFIX_RIGHT)) {
            realId = id.substring(PREFIX_LENGTH);
        } else {
            realId = id;
        }

        try {
            switch (realId) {
                case "Factory":
                    // Looks like the "Factory" time zone is UTC.
                    return ZoneOffset.UTC;
                case "America/Nuuk":
                    // They are same timezone including DST.
                    return ZoneId.of("America/Godthab");
                case "ROC":
                    // Republic of China, 1912-1949, very very old time zone.
                    // Even the ZoneId.SHORT_IDS does not support it.
                    // Is there anyone using this time zone, really?
                    // Don't think so, but should support it for compatible.
                    // Just use GMT+8, id is equal to +08:00.
                    return ZoneId.of("+8");
            }

            return ZoneId.of(realId, ZoneId.SHORT_IDS);
        } catch (DateTimeException e) {
            logger.warn("The server timezone is unknown <{}>, trying to use system default timezone", id, e);

            return ZoneId.systemDefault();
        }
    }

    private static IsolationLevel convertIsolationLevel(@Nullable String name) {
        if (name == null) {
            logger.warn("Isolation level is null in current session, fallback to repeatable read");

            return IsolationLevel.REPEATABLE_READ;
        }

        switch (name) {
            case "READ-UNCOMMITTED":
                return IsolationLevel.READ_UNCOMMITTED;
            case "READ-COMMITTED":
                return IsolationLevel.READ_COMMITTED;
            case "REPEATABLE-READ":
                return IsolationLevel.REPEATABLE_READ;
            case "SERIALIZABLE":
                return IsolationLevel.SERIALIZABLE;
        }

        logger.warn("Unknown isolation level {} in current session, fallback to repeatable read", name);

        return IsolationLevel.REPEATABLE_READ;
    }

    private static long convertLockWaitTimeout(@Nullable Long timeout) {
        if (timeout == null) {
            logger.error("Lock wait timeout is null, fallback to " + DEFAULT_LOCK_WAIT_TIMEOUT + " seconds");

            return DEFAULT_LOCK_WAIT_TIMEOUT;
        }

        return timeout;
    }

    /**
     * Resolves the column of session isolation level, the {@literal @@tx_isolation} has been marked as
     * deprecated.
     * <p>
     * If server is MariaDB, {@literal @@transaction_isolation} is used starting from {@literal 11.1.1}.
     * <p>
     * If the server is MySQL, use {@literal @@transaction_isolation} starting from {@literal 8.0.3}, or
     * between {@literal 5.7.20} and {@literal 8.0.0} (exclusive).
     */
    private static String transactionIsolationColumn(ConnectionContext context) {
        ServerVersion version = context.getServerVersion();

        if (context.isMariaDb()) {
            return version.isGreaterThanOrEqualTo(MARIA_11_1_1) ? "@@transaction_isolation AS i" :
                "@@tx_isolation AS i";
        }

        return version.isGreaterThanOrEqualTo(MYSQL_8_0_3) ||
            (version.isGreaterThanOrEqualTo(MYSQL_5_7_20) && version.isLessThan(MYSQL_8)) ?
            "@@transaction_isolation AS i" : "@@tx_isolation AS i";
    }

    private static class InitData {

        private final IsolationLevel level;

        private final long lockWaitTimeout;

        @Nullable
        private final String product;

        @Nullable
        private final ZoneId serverZoneId;

        private InitData(IsolationLevel level, long lockWaitTimeout, @Nullable String product,
            @Nullable ZoneId serverZoneId) {
            this.level = level;
            this.lockWaitTimeout = lockWaitTimeout;
            this.product = product;
            this.serverZoneId = serverZoneId;
        }
    }
}
