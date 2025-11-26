import com.datadog.ServerlessCompatAgent;
import java.lang.reflect.Method;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import static org.junit.jupiter.api.Assertions.*;

class ServerlessCompatAgentTest {

    @ParameterizedTest
    @CsvSource({
            "TRACE, TRACE",
            "DEBUG, DEBUG",
            "INFO, INFO",
            "WARN, WARN",
            "ERROR, ERROR",
            "CRITICAL, ERROR",
            "OFF, null"
    })
    void testInitLogger(String ddLogLevel, String expectedSlf4jLevel) throws Exception {
        Method initLoggerMethod = ServerlessCompatAgent.class.getDeclaredMethod("initLogger", String.class);
        initLoggerMethod.setAccessible(true);
        Logger logger = (Logger) initLoggerMethod.invoke(null, ddLogLevel);

        if ("OFF".equals(ddLogLevel)) {
            assertTrue(logger instanceof NOPLogger);
        } else {
            assertEquals(expectedSlf4jLevel,
                    System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
        }
    }
}
