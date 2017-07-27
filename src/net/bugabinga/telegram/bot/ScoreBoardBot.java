/**
 * © 2017 Oliver Jan Krylow <oliver@bugabinga.net> ❤
 */
package net.bugabinga.telegram.bot;

import static java.nio.file.Files.*;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

import org.eclipse.jdt.annotation.Nullable;
import org.telegram.telegrambots.*;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;
import org.telegram.telegrambots.generics.BotSession;
import org.telegram.telegrambots.logging.BotLogger;

/**
 * @author Oliver Jan Krylow <oliver@bugabinga.net>
 * @since 20.07.2017
 *
 */
public class ScoreBoardBot extends TelegramLongPollingBot {

  /**
   * Name of the bot as known to the Telegram Bot API.
   */
  private static final String SCOBO_BOT = "scobo_bot";

  private final Queue<Update> eventLog;

  private final ExecutorService executor;

  /**
   * Constructs a Score Board bot with its thread executor and queue it is going to manage.
   */
  public ScoreBoardBot() {
    /*
     * The file name ending is technically irrelevant. It is JSON now so that my editor highlights
     * it correctly right away...
     */
    @Nullable
    File eventLogFile = null;
    final String name = SCOBO_BOT + ".json";
    try {
      /*
       * Storing app data into "$userhome/.local/share/scobo_bot/". This may be not the "standard
       * location as defined in the FHS, but this way we do not need root or some setup code. No
       * Windows support planned.
       */
      final Path eventLogPath =
          Paths.get(System.getProperty("user.home"), ".local", "share", SCOBO_BOT, name);

      if (notExists(eventLogPath)) {
        createDirectories(eventLogPath.getParent());
        createFile(eventLogPath);
      }

      eventLogFile = eventLogPath.toFile();
    } catch (final IOException exception) {
      BotLogger.error("Could not open " + name + ". Giving up and crashing the bot!", SCOBO_BOT,
          exception);
      System.exit(-1);
    }

    requireNonNull(eventLogFile);

    eventLog = new ConcurrentLinkedQueue<>();
    executor = Executors.newSingleThreadExecutor();
    executor.execute(new EventLogProcessor(eventLog, eventLogFile));

    BotLogger.info(SCOBO_BOT, "Created the Score Board Bot! Ready for action!");
  }

  @Override
  public void onClosing() {
    try {
      executor.awaitTermination(30, SECONDS);
    } catch (final InterruptedException exception) {
      BotLogger.error(
          "We got interrupted while waiting for the executor to shutdown. Will now forcefully shutdown, some events may be lost.",
          SCOBO_BOT, exception);
      final List<Runnable> unableToShutdownTasks = executor.shutdownNow();
      BotLogger.warn(SCOBO_BOT, String.format("%d tasks could not terminate normally. %s.",
          Integer.valueOf(unableToShutdownTasks.size()), unableToShutdownTasks.toString()));
    }
    super.onClosing();
  }

  @Override
  public String getBotUsername() {
    return SCOBO_BOT;
  }

  @Override
  public void onUpdateReceived(final @Nullable Update update) {
    BotLogger.info(SCOBO_BOT, "Received an update from Telegram: " + update);

    if (update == null) {
      BotLogger.warn(SCOBO_BOT, "The incoming update was null. Seems like a bug in the API?");
      return;
    }

    /*
     * The update gets added to out Event Log. There it immediately will be persisted to disk and
     * then the commands get processed.
     */
    final boolean success = eventLog.offer(update);

    if (!success) {
      BotLogger.error(SCOBO_BOT,
          "Could not add the update to the event log. There might be too much pressure on it. Low RAM? Low disk space?");
    }
  }

  @Override
  public String getBotToken() {
    return "407074217:AAH-4zBmKeNlNH4lV448vy8OgnCs5ZsYvfc";
  }

  /**
   * @param args ignored for now.
   */
  public static void main(final String[] args) {
    BotLogger.setLevel(Level.INFO);

    ApiContextInitializer.init();
    BotLogger.info(SCOBO_BOT, "Initialized the API context, whatever that is...");

    // TODO use keystore for TOKEN
    final TelegramBotsApi botsApi = new TelegramBotsApi();
    BotLogger.info(SCOBO_BOT, "Created the Telegram Bots Api!");

    try {
      final BotSession scoboSession = botsApi.registerBot(new ScoreBoardBot());
      BotLogger.info(SCOBO_BOT, "Registered the " + SCOBO_BOT
          + " bot with the API! We got a session which is running? " + scoboSession.isRunning());
    } catch (final TelegramApiException exception) {
      BotLogger.error("Failed to register the bot with the API. Exit.", SCOBO_BOT, exception);
      System.exit(-1);
    }
  }

}
