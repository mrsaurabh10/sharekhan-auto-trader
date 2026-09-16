package org.com.sharekhan.migration;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScriptMasterTickSchemaTest {
    @Test
    void addsTickColumnToExistingRowsAndSupportsFreshDatabases() throws Exception {
        String schema;
        try (var resource = getClass().getResourceAsStream("/db/postgresql/reference-data-schema.sql")) {
            schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] statements = schema.split(";");
        for (boolean existing : new boolean[]{true, false}) {
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:tick_" + existing + ";MODE=PostgreSQL");
                 var statement = connection.createStatement()) {
                if (existing) {
                    statement.execute("CREATE TABLE script_master (scrip_code INTEGER PRIMARY KEY, trading_symbol VARCHAR(255))");
                    statement.execute("INSERT INTO script_master VALUES (22377, 'MAXHEALTH')");
                }
                statement.execute(statements[0]);
                statement.execute(statements[1]);
                // Reapplying the upgrade must also be safe.
                statement.execute(statements[0]);
                if (!existing) statement.execute("INSERT INTO script_master (scrip_code, trading_symbol) VALUES (22377, 'MAXHEALTH')");
                statement.execute("UPDATE script_master SET tick_size=0.1 WHERE scrip_code=22377");
                try (var rows = statement.executeQuery("SELECT trading_symbol,tick_size FROM script_master WHERE scrip_code=22377")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("MAXHEALTH");
                    assertThat(rows.getDouble(2)).isEqualTo(0.1);
                }
            }
        }
    }
}
