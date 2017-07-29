/**
 * © 2017 Oliver Jan Krylow <oliver@bugabinga.net> ❤
 */
package net.bugabinga.telegram.bot;

import static java.lang.System.lineSeparator;
import static java.lang.Thread.currentThread;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Queue;

import org.eclipse.jdt.annotation.Nullable;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.logging.BotLogger;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
  private final ObjectMapper jsonMapper;
  private final File eventLogFile;

  /**
   * @param eventLog A queue, that is filled by another thread with Telegram {@link Update}s.
   * @param eventLogFile The {@link File} on disk where events get written to.
   * @param jsonMapper The JSON serializer.
   */
  public EventLogProcessor(final Queue<Update> eventLog, final File eventLogFile,
      final ObjectMapper jsonMapper) {
    this.eventLog = eventLog;
    this.eventLogFile = eventLogFile;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void run() {
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

      /*
       * Creating a new output stream in the loop is very wasteful, but the object mapper from
       * jackson unfortunately closes the stream it writes to so we are forced to. FIXME(oliver):
       * There is surely a workaround for this. jackson has many write methods...
       */
      try (final FileOutputStream fileOutputStream = new FileOutputStream(eventLogFile, true)) {
        fileOutputStream.write(lineSeparator().getBytes(UTF_8));
        // Warning! the writeValue method automatically closes the output stream, so do not touch it
        // afterwards...
        jsonMapper.writeValue(fileOutputStream, update);
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
      }
    }
  }
}
