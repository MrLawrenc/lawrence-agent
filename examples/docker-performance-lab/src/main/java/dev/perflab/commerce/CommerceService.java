package dev.perflab.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Small, repeatable order flow for observing business methods and real dependencies. */
@Service
public class CommerceService {
    public record Product(String sku, String name, long priceCents, int stock) { }
    public record OrderView(String id, String customerId, String sku, String productName,
                            int quantity, long totalCents, String status, String createdAt) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final JedisPooled redis;
    private final String rabbitHost;
    private final int rabbitPort;

    public CommerceService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                           ObjectMapper json,
                           @Value("${LAB_REDIS_HOST:redis}") String redisHost,
                           @Value("${LAB_REDIS_PORT:6379}") int redisPort,
                           @Value("${LAB_RABBIT_HOST:rabbitmq}") String rabbitHost,
                           @Value("${LAB_RABBIT_PORT:5672}") int rabbitPort) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.redis = new JedisPooled(redisHost, redisPort);
        this.rabbitHost = rabbitHost;
        this.rabbitPort = rabbitPort;
        seedCatalog();
    }

    private void seedCatalog() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS demo_products (sku VARCHAR(32) PRIMARY KEY, "
                + "name VARCHAR(80) NOT NULL, price_cents BIGINT NOT NULL, stock INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS demo_orders (id VARCHAR(36) PRIMARY KEY, "
                + "customer_id VARCHAR(40) NOT NULL, sku VARCHAR(32) NOT NULL, "
                + "quantity INTEGER NOT NULL, total_cents BIGINT NOT NULL, "
                + "status VARCHAR(24) NOT NULL, created_at VARCHAR(40) NOT NULL)");
        jdbc.update("INSERT INTO demo_products (sku,name,price_cents,stock) VALUES (?,?,?,?) "
                + "ON CONFLICT (sku) DO NOTHING", "BOOK-JAVA", "Java 性能实战", 12900L, 500);
        jdbc.update("INSERT INTO demo_products (sku,name,price_cents,stock) VALUES (?,?,?,?) "
                + "ON CONFLICT (sku) DO NOTHING", "MUG-TRACE", "调用链马克杯", 5900L, 500);
        jdbc.update("INSERT INTO demo_products (sku,name,price_cents,stock) VALUES (?,?,?,?) "
                + "ON CONFLICT (sku) DO NOTHING", "KIT-OPS", "运维工具套装", 21900L, 500);
    }

    public List<Product> catalog() {
        return jdbc.query("SELECT sku,name,price_cents,stock FROM demo_products ORDER BY sku",
                (row, number) -> new Product(row.getString("sku"), row.getString("name"),
                        row.getLong("price_cents"), row.getInt("stock")));
    }

    public OrderView checkout(String customerId, String sku, int quantity) throws Exception {
        if (customerId == null || customerId.isBlank() || sku == null || sku.isBlank()
                || quantity < 1 || quantity > 5) {
            throw new IllegalArgumentException("customerId、sku 必填，quantity 为 1–5");
        }
        OrderView order = transactions.execute(status -> reserveAndCreate(customerId, sku, quantity));
        if (order == null) throw new IllegalStateException("订单事务未返回结果");
        publishOrderCreated(order);
        redis.setex("demo:order:" + order.id(), 300, json.writeValueAsString(order));
        return order;
    }

    private OrderView reserveAndCreate(String customerId, String sku, int quantity) {
        List<Product> products = jdbc.query("SELECT sku,name,price_cents,stock FROM demo_products WHERE sku=?",
                (row, number) -> new Product(row.getString("sku"), row.getString("name"),
                        row.getLong("price_cents"), row.getInt("stock")), sku);
        if (products.isEmpty()) throw new IllegalArgumentException("商品不存在: " + sku);
        Product product = products.get(0);
        int reserved = jdbc.update("UPDATE demo_products SET stock=stock-? WHERE sku=? AND stock>=?",
                quantity, sku, quantity);
        if (reserved != 1) throw new IllegalStateException("库存不足: " + sku);
        OrderView order = new OrderView(UUID.randomUUID().toString(), customerId, sku,
                product.name(), quantity, Math.multiplyExact(product.priceCents(), quantity),
                "PLACED", Instant.now().toString());
        jdbc.update("INSERT INTO demo_orders (id,customer_id,sku,quantity,total_cents,status,created_at) "
                        + "VALUES (?,?,?,?,?,?,?)", order.id(), order.customerId(), order.sku(),
                order.quantity(), order.totalCents(), order.status(), order.createdAt());
        return order;
    }

    public OrderView findOrder(String id) throws Exception {
        String cached = redis.get("demo:order:" + id);
        if (cached != null) return json.readValue(cached, OrderView.class);
        return jdbc.query("SELECT o.id,o.customer_id,o.sku,p.name,o.quantity,o.total_cents,"
                        + "o.status,o.created_at FROM demo_orders o JOIN demo_products p ON p.sku=o.sku "
                        + "WHERE o.id=?",
                (row, number) -> new OrderView(row.getString("id"), row.getString("customer_id"),
                        row.getString("sku"), row.getString("name"), row.getInt("quantity"),
                        row.getLong("total_cents"), row.getString("status"),
                        row.getString("created_at")), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + id));
    }

    public List<OrderView> recentOrders() {
        return jdbc.query("SELECT o.id,o.customer_id,o.sku,p.name,o.quantity,o.total_cents,"
                        + "o.status,o.created_at FROM demo_orders o JOIN demo_products p ON p.sku=o.sku "
                        + "ORDER BY o.created_at DESC LIMIT 20",
                (row, number) -> new OrderView(row.getString("id"), row.getString("customer_id"),
                        row.getString("sku"), row.getString("name"), row.getInt("quantity"),
                        row.getLong("total_cents"), row.getString("status"),
                        row.getString("created_at")));
    }

    private void publishOrderCreated(OrderView order) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitHost);
        factory.setPort(rabbitPort);
        factory.setUsername("lab");
        factory.setPassword("lab-only");
        factory.setAutomaticRecoveryEnabled(false);
        try (com.rabbitmq.client.Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            channel.queueDeclare("demo.order.created", true, false, false, null);
            channel.confirmSelect();
            channel.basicPublish("", "demo.order.created",
                    new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(),
                    json.writeValueAsBytes(order));
            channel.waitForConfirmsOrDie(3000);
        }
    }

    @PreDestroy
    public void close() {
        redis.close();
    }
}
