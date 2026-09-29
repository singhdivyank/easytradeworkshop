package com.dynatrace.easytrade.bitcoinpaymentservice;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentStatus;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.PaymentStatusType;

/**
 * All database access for the bitcoin payment service. Uses parameterized statements only
 * (injection-safe) and borrows connections from a pooled {@link DataSource} rather than
 * opening a fresh DriverManager connection per call. Payment state is stored append-only in
 * BitcoinPaymentStatus so the lifecycle is auditable and can be replayed.
 */
public class DatabaseHelper {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseHelper.class);

    private static final String INSERT_PAYMENT_QUERY =
            "INSERT INTO [dbo].[BitcoinPayments] ([Id], [AccountId], [Amount], [Currency], [WalletAddress]) "
                    + "VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_STATUS_QUERY =
            "INSERT INTO [dbo].[BitcoinPaymentStatus] ([BitcoinPaymentId], [Timestamp], [Status], [Details]) "
                    + "VALUES (?, ?, ?, ?)";
    private static final String GET_LATEST_STATUS_QUERY =
            "SELECT TOP 1 s.[BitcoinPaymentId], p.[AccountId], s.[Status], s.[Timestamp], s.[Details] "
                    + "FROM [dbo].[BitcoinPaymentStatus] s "
                    + "JOIN [dbo].[BitcoinPayments] p ON p.[Id] = s.[BitcoinPaymentId] "
                    + "WHERE s.[BitcoinPaymentId] = ? ORDER BY s.[Timestamp] DESC, s.[Id] DESC";
    private static final String GET_PAYMENTS_IN_STATUS_QUERY =
            "SELECT x.[BitcoinPaymentId] FROM [dbo].[BitcoinPaymentStatus] x "
                    + "JOIN (SELECT MAX([Id]) AS [Id], [BitcoinPaymentId] FROM [dbo].[BitcoinPaymentStatus] "
                    + "GROUP BY [BitcoinPaymentId]) y "
                    + "ON x.[BitcoinPaymentId] = y.[BitcoinPaymentId] AND x.[Id] = y.[Id] "
                    + "WHERE x.[Status] = ?";

    private final DataSource dataSource;

    public DatabaseHelper(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Persist a new payment and its initial PENDING status in a single transaction, then
     * return the generated payment id. Kept fast and synchronous so the API can return 202
     * immediately; the actual settlement happens asynchronously in the scheduler.
     */
    public String insertNewPayment(BitcoinPaymentRequest request) throws SQLException {
        String paymentId = UUID.randomUUID().toString();
        try (Connection conn = getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement query = conn.prepareStatement(INSERT_PAYMENT_QUERY)) {
                    query.setString(1, paymentId);
                    query.setInt(2, request.accountId());
                    query.setBigDecimal(3, request.amount() != null ? request.amount() : BigDecimal.ZERO);
                    query.setString(4, request.currency());
                    query.setString(5, request.walletAddress());
                    query.executeUpdate();
                }
                insertStatus(conn, paymentId, PaymentStatusType.PENDING, PaymentStatusType.PENDING.getType());
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }
        return paymentId;
    }

    public void insertStatus(String paymentId, PaymentStatusType statusType, String details) throws SQLException {
        try (Connection conn = getConnection()) {
            insertStatus(conn, paymentId, statusType, details);
        }
    }

    public void insertStatus(Connection conn, String paymentId, PaymentStatusType statusType, String details)
            throws SQLException {
        Timestamp timestamp = Timestamp.valueOf(OffsetDateTime.now().toLocalDateTime());
        logger.debug("Inserting status [paymentId::{}] [status::{}] [details::{}]", paymentId, statusType.getType(),
                details);
        try (PreparedStatement query = conn.prepareStatement(INSERT_STATUS_QUERY)) {
            query.setString(1, paymentId);
            query.setTimestamp(2, timestamp);
            query.setString(3, statusType.getType());
            query.setString(4, details);
            query.executeUpdate();
        }
    }

    public Optional<BitcoinPaymentStatus> getLatestStatus(String paymentId) throws SQLException {
        try (Connection conn = getConnection();
                PreparedStatement query = conn.prepareStatement(GET_LATEST_STATUS_QUERY)) {
            query.setString(1, paymentId);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp ts = rs.getTimestamp("Timestamp");
                OffsetDateTime timestamp = ts != null
                        ? OffsetDateTime.ofInstant(ts.toInstant(), ZoneId.of("UTC"))
                        : null;
                return Optional.of(new BitcoinPaymentStatus(
                        rs.getString("BitcoinPaymentId"),
                        rs.getInt("AccountId"),
                        rs.getString("Status"),
                        timestamp,
                        rs.getString("Details")));
            }
        }
    }

    /**
     * Return payment ids whose latest status equals the given status — used by the settlement
     * scheduler to pick up work without scanning the whole table.
     */
    public List<String> getPaymentIdsInStatus(Connection conn, PaymentStatusType statusType) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement query = conn.prepareStatement(GET_PAYMENTS_IN_STATUS_QUERY)) {
            query.setString(1, statusType.getType());
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("BitcoinPaymentId"));
                }
            }
        }
        return ids;
    }
}
