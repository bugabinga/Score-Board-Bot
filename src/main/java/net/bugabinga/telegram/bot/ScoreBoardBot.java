/**
 * © 2017 Oliver Jan Krylow <oliver@bugabinga.net> ❤
 */
package net.bugabinga.telegram.bot;

import static java.lang.String.format;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.notExists;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.telegram.telegrambots.api.methods.ActionType.TYPING;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.logging.Level;

import org.apache.commons.io.input.ReversedLinesFileReader;
import org.eclipse.jdt.annotation.Nullable;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.TelegramBotsApi;
import org.telegram.telegrambots.api.methods.send.SendChatAction;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.api.objects.User;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;
import org.telegram.telegrambots.generics.BotSession;
import org.telegram.telegrambots.logging.BotLogger;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Oliver Jan Krylow <oliver@bugabinga.net>
 * @since 20.07.2017
 *
 */
public class ScoreBoardBot extends TelegramLongPollingBot {

  /**
   * Constant value of 1.
   */
  private static final Integer ONE = Integer.valueOf(1);

  private static BiFunction<? super Integer, ? super Integer, ? extends Integer> adderOfIntegers =
      (previousScore, point) -> Integer.valueOf(previousScore.intValue() + point.intValue());

  private static final BiFunction<? super String, ? super Integer, ? extends @Nullable Integer> decrementOrRemoveIfSmallerThanZero =
      (__, currentScore) -> {
        final int newScore = currentScore.intValue() - 1;
        if (newScore < 0) {
          return null; // will remove the the from the map
        }
        return Integer.valueOf(newScore);
      };

  /*
   * Taken some emojis from
   * https://stackoverflow.com/questions/34433308/java-based-telegram-bot-api-how-to-send-emojis#
   * 35523951 which are meant to convey "success".
   */
  private static final String[] SUCCESS_EMOJIS =
      new String[] {"\uD83D\uDC4F", "\uD83C\uDF89", "\uD83D\uDE0E", "\uD83D\uDE2C"};

  private static final String[] CONGRATZ_TEXT =
      new String[] {"gg", "wp", "gratz", "nice", "gj", "you rock"};

  /**
   * Name of the bot as known to the Telegram Bot API.
   */
  private static final String SCOBO_BOT = "scobo_bot";

  /**
   * This command gets used by people that want to increase their score by 1.
   */
  private static final String WON_COMMAND = "/won";

  /**
   * This command undoes the last command that altered the score.
   */
  private static final String UNDO_COMMAND = "/undo";

  /**
   * This command shows a table with the current scores for all people.
   */
  private static final String BOARD_COMMAND = "/board";


  private final Queue<Update> eventLog;

  private final ExecutorService executor;

  private final Path eventLogPath;

  private final ObjectMapper mapper;

  /**
   * Constructs a Score Board bot with its thread executor and queue it is going to manage.
   *
   * @throws IOException If the event log file cannot be accessed
   */
  public ScoreBoardBot() throws IOException {
    mapper = new ObjectMapper();
    eventLog = new ConcurrentLinkedQueue<>();
    eventLogPath = initializeEventLogFile();
    executor = Executors.newSingleThreadExecutor();
    executor.execute(new EventLogProcessor(eventLog, eventLogPath.toFile(), mapper));

    BotLogger.info(SCOBO_BOT, "Created the Score Board Bot! Ready for action!");
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

      final SendMessage fail = new SendMessage(update.getMessage().getChatId(),
          "final Oh crap! final I failed to do that final command. Please sorry and very try again!");
      try {
        execute(fail);
      } catch (final TelegramApiException exception) {
        BotLogger.error("Failed to send message '" + fail + "' to the people.", SCOBO_BOT,
            exception);
      }
    } else {
      /*
       * There is an implicit assumption here, that it is OK if the event log writer thread has not
       * yet finished writing, because the current update itself is never relevant for the message
       * we are going to send to the chat people.
       */
      try {
        processUpdate(update);
      } catch (final TelegramApiException exception) {
        BotLogger.error("Failed to send a message to the people.", SCOBO_BOT, exception);
      } catch (final IOException exception) {
        BotLogger.error("Failed to open the event log file!", SCOBO_BOT, exception);
      }
    }
  }

  /**
   * @param update
   * @throws TelegramApiException
   * @throws IOException
   */
  private void processUpdate(final Update update) throws TelegramApiException, IOException {
    final @Nullable Message message = update.getMessage();

    if (message == null) {
      BotLogger.info(SCOBO_BOT, "The incoming update has no message. Ignoring it!");
      return;
    }

    final Long chatId = message.getChatId();
    requireNonNull(chatId);
    final String text = message.getText();

    if (text == null) {
      BotLogger.debug(SCOBO_BOT, "The message from the update contained no text. So useless.");
      return;
    }

    if (text.startsWith(WON_COMMAND)) {
      final @Nullable String sendersName = extractSendersName(message.getFrom());
      if (sendersName != null) {
        processWonCommand(chatId, sendersName);
      }
    } else if (text.startsWith(UNDO_COMMAND)) {
      processUndoCommand(chatId);
    } else if (text.startsWith(BOARD_COMMAND)) {
      processBoardCommand(chatId);
    } else {
      BotLogger.info(SCOBO_BOT, "We have received an unknown command: " + text);
      execute(
          new SendMessage(chatId, "Dude, I don´t know what to do with that. Try again douchbag!"));
    }
  }

  private void processWonCommand(final Long chatId, final String username)
      throws TelegramApiException {
    execute(new SendMessage(chatId, pickRandom(SUCCESS_EMOJIS) + ' ' + pickRandom(CONGRATZ_TEXT)
        + ", " + username + "! +1 pointz."));
  }

  private void processUndoCommand(final Long chatId) throws TelegramApiException, IOException {
    // since there is some file IO involved, let people know this might take a while...
    execute(new SendChatAction(chatId, TYPING.toString()));

    try (final ReversedLinesFileReader reversedLinesFileReader =
        new ReversedLinesFileReader(eventLogPath.toFile(), StandardCharsets.UTF_8)) {
      @Nullable
      String username = null;

      @Nullable
      String line;
      while ((line = reversedLinesFileReader.readLine()) != null) {
        if (line.isEmpty()) {
          BotLogger.debug(SCOBO_BOT,
              "Line of data in event log file is empty. Stopping further processing.");
          continue;
        }

        if (!line.startsWith(WON_COMMAND)) {
          BotLogger.debug(SCOBO_BOT,
              "Line of data in event log file does not even contain the won command. Stopping further processing.");
          continue;
        }

        final @Nullable Update savedUpdate = toUpdateObject(line);
        if (savedUpdate == null) {
          BotLogger.debug(SCOBO_BOT,
              "The saved update object is null! Check the event log file for corruption.");
          continue;
        }

        @Nullable
        final Long savedChatId = savedUpdate.getMessage().getChatId();

        if (savedChatId == null) {
          BotLogger.error(SCOBO_BOT,
              "The event log file contained an Update with a Message without a Chat ID. Check if the event log file is valid or if it got corrupted.");
          continue;
        }

        if (!savedChatId.equals(chatId)) {
          BotLogger.debug(SCOBO_BOT, "The update with id " + savedChatId
              + " is not the current chat " + chatId + ". So we ignore it.");
          continue;
        }

        final @Nullable Message savedMessage = savedUpdate.getMessage();
        if (savedMessage == null) {
          BotLogger.debug(SCOBO_BOT,
              "The saved update object in the event log file has no message! Id of wonky update: "
                  + savedUpdate.getUpdateId());
          continue;
        }

        final @Nullable String savedText = savedMessage.getText();
        if (savedText == null) {
          BotLogger.debug(SCOBO_BOT,
              "The saved update object in the event log file has no message text! Id of wonky update: "
                  + savedUpdate.getUpdateId());
          continue;
        }

        if (savedText.startsWith(WON_COMMAND)) {
          final @Nullable String sendersName = extractSendersName(savedMessage.getFrom());
          if (sendersName != null) {
            username = sendersName;
            break;
          }
        }
      }

      if (username == null) {
        execute(new SendMessage(chatId, "There is nothing to undo yet, fool!"));
      } else {
        final SendMessage message = new SendMessage(chatId,
            format("_yessir!_ the last score adjustment from *%s* will be undone!", username));
        message.enableMarkdown(true);
        execute(message);
      }
    }
  }

  private void processBoardCommand(final Long chatId) throws TelegramApiException, IOException {
    // since there is some file IO involved, let people know this might take a while...
    execute(new SendChatAction(chatId, TYPING.toString()));

    final Map<String, Integer> scores = new HashMap<>();
    Files.lines(eventLogPath, StandardCharsets.UTF_8).filter(line -> !line.isEmpty())
        .map(this::toUpdateObject).forEach(savedUpdate -> {
          if (savedUpdate == null) {
            BotLogger.error(SCOBO_BOT,
                "The event log file containes a line with an invalid Update object. It could not be parsed. Is the file still valid?");
            return;
          }

          final @Nullable Message savedMessage = savedUpdate.getMessage();
          if (savedMessage == null) {
            BotLogger.debug(SCOBO_BOT,
                "The saved update object in the event log file has no message! Id of wonky update: "
                    + savedUpdate.getUpdateId());
            return;
          }

          final @Nullable Long savedChatId = savedMessage.getChatId();

          if (savedChatId == null) {
            BotLogger.error(SCOBO_BOT,
                "The event log file contained an Update with a Message without a Chat ID. Check if the event log file is valid or if it got corrupted.");
            return;
          }

          if (!savedChatId.equals(chatId)) {
            BotLogger.debug(SCOBO_BOT, "The update with id " + savedChatId
                + " is not the current chat " + chatId + ". So we ignore it.");
            return;
          }

          final @Nullable String sendersName = extractSendersName(savedMessage.getFrom());

          if (sendersName == null) {
            BotLogger.debug(SCOBO_BOT,
                "The saved update object in the event log file has no message sender! Id of wonky update: "
                    + savedUpdate.getUpdateId());
            return;
          }

          @Nullable
          final String text = savedMessage.getText();

          if (text == null) {
            BotLogger.debug(SCOBO_BOT,
                "The saved update object in the event log has no text! Id of weird update: "
                    + savedUpdate.getUpdateId());
            return;
          }

          /*
           * The following logic is the heart of the current implementation of the API. It is simple
           * currently, because we have only 2 commands that can alter the score.
           *
           * won --> +1 for the user
           *
           * undo --> -1 for the user, unless score gets smaller than 0
           *
           * In the future, it is likely, that undo needs to be reimplemented depending on the types
           * of commands we add.
           */
          if (text.startsWith(WON_COMMAND)) {
            scores.merge(sendersName, ONE, adderOfIntegers);
          } else if (text.startsWith(UNDO_COMMAND)) {
            scores.computeIfPresent(sendersName, decrementOrRemoveIfSmallerThanZero);
          } else {
            BotLogger.debug(SCOBO_BOT,
                "The command '" + text + "' is not relevant for the command " + BOARD_COMMAND);
          }
        });

    BotLogger.info(SCOBO_BOT, "These scores where found in the event log: " + scores);

    String scoreBoardSummary = scores.entrySet().stream()
        .map(entry -> "*" + entry.getKey() + "*:\t" + entry.getValue() + " pts.")
        .collect(joining("\n"));

    if (scoreBoardSummary.isEmpty()) {
      scoreBoardSummary = "Nobody has any points, yet. *LOL*";
    }
    final SendMessage scoreBoardSimple = new SendMessage(chatId, scoreBoardSummary);
    scoreBoardSimple.enableMarkdown(true);

    execute(scoreBoardSimple);
  }


  private static @Nullable String extractSendersName(final @Nullable User from) {
    if (from == null) {
      BotLogger.warn(SCOBO_BOT,
          "Encountered a scenario which is not yet handled. A message without a sender has been found. It gets ignored for now.");
      return null;
    }
    final @Nullable String userName = from.getUserName();
    return userName == null ? from.getFirstName() : userName;
  }

  /**
   * @param texts Array of strings.
   * @return One string randomly picked out of the array.
   */
  private static String pickRandom(final String[] texts) {
    return requireNonNull(texts[ThreadLocalRandom.current().nextInt(texts.length)]);
  }

  private @Nullable Update toUpdateObject(final String json) {
    try {
      return mapper.readValue(json.getBytes(), Update.class);
    } catch (final IOException exception) {
      BotLogger.error(
          "Could not deserialize processUndoCommandfrom the event log file. Highly suspicious! Must be a bug. But where?",
          SCOBO_BOT, exception);
      return null;
    }
  }

  private static Path initializeEventLogFile() throws IOException {
    /*
     * The file name ending is technically irrelevant. It is JSON now so that my editor highlights
     * it correctly right away...
     */
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

      return eventLogPath;
    } catch (final IOException exception) {
      final String message = "Could not open " + name + ".";
      BotLogger.error(message, SCOBO_BOT, exception);
      throw new IOException(message, exception);
    }
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
  public String getBotToken() {
    final String apiToken = System.getenv("TELEGRAM_API_TOKEN");
    requireNonNull(apiToken,
        "You need to set a telegram api token as given to you by the Botfather! Set the environment variable 'TELEGRAM_API_TOKEN=...' to do that!");
    return apiToken;
  }

  @Override
  public String getBotUsername() {
    return SCOBO_BOT;
  }

  /**
   * @param args ignored for now.
   */
  public static void main(final String[] args) {
    BotLogger.setLevel(Level.INFO);

    ApiContextInitializer.init();
    BotLogger.info(SCOBO_BOT, "Initialized the API context, whatever that is...");

    final TelegramBotsApi botsApi = new TelegramBotsApi();
    BotLogger.info(SCOBO_BOT, "Created the Telegram Bots Api!");

    try {
      final BotSession scoboSession = botsApi.registerBot(new ScoreBoardBot());
      BotLogger.info(SCOBO_BOT, "Registered the " + SCOBO_BOT
          + " bot with the API! We got a session which is running? " + scoboSession.isRunning());
    } catch (final TelegramApiException exception) {
      BotLogger.error("Failed to register the bot with the API. Exit.", SCOBO_BOT, exception);
      System.exit(-1);
    } catch (final IOException exception) {
      BotLogger.error("Giving up and crashing!", SCOBO_BOT, exception);
      System.exit(-1);
    }
  }

}
