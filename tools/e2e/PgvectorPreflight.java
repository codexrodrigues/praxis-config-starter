import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class PgvectorPreflight {
    private PgvectorPreflight() {
    }

    public static void main(String[] args) throws Exception {
        String url = required("CONFIG_DATASOURCE_URL");
        String username = required("CONFIG_DATASOURCE_USERNAME");
        String password = required("CONFIG_DATASOURCE_PASSWORD");
        Class.forName("org.postgresql.Driver");

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            String extensionVersion = scalar(connection,
                    "select extversion from pg_extension where extname = 'vector'");
            if (extensionVersion == null || extensionVersion.isBlank()) {
                throw new IllegalStateException("PostgreSQL extension vector is not installed");
            }

            String tableName = scalar(connection,
                    "select to_regclass('public.vector_store')::text");
            if (!"vector_store".equals(tableName)) {
                throw new IllegalStateException("public.vector_store is not available");
            }

            String embeddingType = scalar(connection, """
                    select format_type(attribute.atttypid, attribute.atttypmod)
                    from pg_attribute attribute
                    join pg_class relation on relation.oid = attribute.attrelid
                    join pg_namespace namespace on namespace.oid = relation.relnamespace
                    where namespace.nspname = 'public'
                      and relation.relname = 'vector_store'
                      and attribute.attname = 'embedding'
                      and attribute.attnum > 0
                      and not attribute.attisdropped
                    """);
            if (embeddingType == null || !embeddingType.startsWith("vector")) {
                throw new IllegalStateException("vector_store.embedding is not backed by the vector type");
            }

            System.out.printf(
                    "{\"schemaVersion\":\"praxis.pgvector-preflight/v1\",\"ready\":true,"
                            + "\"extensionVersion\":\"%s\",\"table\":\"vector_store\","
                            + "\"embeddingType\":\"%s\"}%n",
                    json(extensionVersion),
                    json(embeddingType));
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
