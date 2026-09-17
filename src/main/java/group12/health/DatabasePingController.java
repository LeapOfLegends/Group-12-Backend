package group12.health;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatabasePingController {
    private final DataSource dataSource;

    public DatabasePingController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/db/ping")
    public ResponseEntity<String> ping() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            ResultSet resultSet = statement.executeQuery();

            if (resultSet.next()) {
                return ResponseEntity.ok("DB ping successful");
            }

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("DB ping failed: no result returned");
        } catch (SQLException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("DB ping failed: " + ex.getMessage());
        }
    }
}
