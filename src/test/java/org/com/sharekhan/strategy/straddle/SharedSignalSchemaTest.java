package org.com.sharekhan.strategy.straddle;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
class SharedSignalSchemaTest {
    @Test void additiveMigrationIsRepeatableAndPreservesLegacySessions() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:shared_signal_migration;MODE=PostgreSQL")) {
            try(var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE expiry_straddle_sessions(id varchar(120) PRIMARY KEY)");
                statement.execute("INSERT INTO expiry_straddle_sessions VALUES ('legacy')");
            }
            var ddl=new ClassPathResource("db/postgresql/shared-strategy-signals-schema.sql");
            ScriptUtils.executeSqlScript(connection,ddl);ScriptUtils.executeSqlScript(connection,ddl);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,shared_signal_id FROM expiry_straddle_sessions")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("legacy");assertThat(rows.getString(2)).isNull();
            }
            try(var statement=connection.createStatement()) {
                statement.execute("INSERT INTO shared_straddle_signals VALUES ('one',DATE '2026-10-22','SENSEX','PM','22/10/2026',73200,1,2,20,73164.81,TIMESTAMP '2026-10-22 13:30:00',TIMESTAMP '2026-10-22 13:30:00')");
                assertThatThrownBy(()->statement.execute("INSERT INTO shared_straddle_signals VALUES ('two',DATE '2026-10-22','SENSEX','PM','22/10/2026',73300,3,4,20,73200,TIMESTAMP '2026-10-22 13:30:00',TIMESTAMP '2026-10-22 13:30:00')"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }
}
