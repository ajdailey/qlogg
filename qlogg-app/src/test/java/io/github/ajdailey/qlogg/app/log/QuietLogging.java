package io.github.ajdailey.qlogg.app.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ConfiguratorRank;
import ch.qos.logback.core.spi.ContextAwareBase;
import org.slf4j.Logger;

/**
 * Configures logback for the module's tests: the root level is OFF with no appenders. A test that
 * checks a statement attaches its own appender and sets that logger's level.
 */
@ConfiguratorRank(ConfiguratorRank.CUSTOM_TOP_PRIORITY)
public final class QuietLogging extends ContextAwareBase implements Configurator {
  /** Creates the configurator; logback calls it through {@code ServiceLoader}. */
  public QuietLogging() {
    super();
  }

  @Override
  public ExecutionStatus configure(LoggerContext context) {
    ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    root.detachAndStopAllAppenders();
    root.setLevel(Level.OFF);
    return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
  }
}
