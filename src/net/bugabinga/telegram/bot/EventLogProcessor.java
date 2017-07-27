/**
 * © 2017 Oliver Jan Krylow <oliver@bugabinga.net> ❤
 */
package net.bugabinga.telegram.bot;

import static java.lang.Thread.currentThread;

import java.io.*;
import java.util.Queue;

import org.eclipse.jdt.annotation.Nullable;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.logging.BotLogger;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.*;

/**
 * @author Oliver Jan Krylow <oliver@bugabinga.net>
 * @since 21.07.2017
 *
 */
public class EventLogProcessor implements Runnable {

  /**
   * Namespace for logger.
   */
  private static final String TAG = "EVENT_LOG_PROCESSOR";
  private static final long SLEEP_TIME = 1000;

  private final Queue<Update> eventLog;
  private final File eventLogFile;
  private final ObjectMapper jsonMapper;

  /**
   * @param eventLog A queue, that is filled by another thread with Telegram {@link Update}s.
   * @param eventLogFile The {@link File} on disk where events get written to.
   */
  public EventLogProcessor(final Queue<Update> eventLog, final File eventLogFile) {
    this.eventLog = eventLog;
    this.eventLogFile = eventLogFile;
    jsonMapper = new ObjectMapper();
  }

  @Override
  public void run() {
    currentThread().setPriority(Thread.MIN_PRIORITY);
    currentThread().setName("Telegram Event Log Processor");

    while (currentThread().isAlive()) {
      @Nullable
      final Update update = eventLog.poll();

      if (update == null) {
        /*
         * The event log is currently empty so we pause the thread an try again later.
         */
        try {
          Thread.sleep(SLEEP_TIME);
        } catch (final InterruptedException exception) {

          BotLogger.warn(
              "This thread got interrupted while sleeping. This is unexpected but not fatal. This thread will now stop running.",
              TAG, exception);

          break;
        }

        // skipping the rest of the process in order to get a new update object
        continue;
      }

      try {
        jsonMapper.writeValue(eventLogFile, update);
      } catch (final JsonGenerationException exception) {
        BotLogger.warn(
            "The serializer failed during generation of output. This incident will be ignored. The following event will be lost : '"
                + update,
            TAG, exception);
      } catch (final JsonMappingException exception) {
        BotLogger.error(
            "The class we wanted to serialize (or one of its field classes...) could not be handled by ther serializer. This is a fatal bug. Stopping now.",
            TAG, exception);
      } catch (final IOException exception) {
        BotLogger.error(
            "The file where the events are supposed to be logged could either not be found or opened. This might be a permission issue or no disk space is left. Either way, it is game over for us, bye!",
            TAG, exception);
        break;
      }
    }

  }

}
