package de.hugosmpmoney;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HugoSMP Money - portiert auf Minecraft 26.3 (Mojang-Mappings).
 *
 * Änderungen gegenüber der 1.21.11-Version (nur Namen/API, kein Verhalten):
 *  - Intermediary-Namen (class_xxx / method_xxx) -> offizielle Mojang-Namen
 *  - ClientCommandManager        -> ClientCommands          (Fabric API 26.1+)
 *  - DrawContext                 -> GuiGraphicsExtractor    (26.2+)
 *  - drawStringWithShadow(...)   -> text(..., true)
 *  - Identifier.of(...)          -> Identifier.fromNamespaceAndPath(...)
 */
public class HugoSmpMoneyClient implements ClientModInitializer {

    public static final String MOD_ID = "hugosmpmoney";

    // Text-Farbe für das HUD (ab 1.21.6 muss der Alpha-Kanal gesetzt sein, sonst ist der Text unsichtbar)
    private static final int WHITE = 0xFFFFFFFF;

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG =
            FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("hugosmp-money.json");

    /*
     * HugoSMP Nachricht:
     *
     * [HugoSMP] Dein Kontostand: $19.178.164,60
     */
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "\\[HugoSMP\\]\\s*Dein Kontostand:\\s*\\$\\s*([0-9.]+)(?:,([0-9]{1,2}))?",
            Pattern.CASE_INSENSITIVE
    );

    private static final DecimalFormat MONEY_FORMAT;

    static {
        DecimalFormatSymbols symbols =
                new DecimalFormatSymbols(Locale.GERMANY);

        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');

        MONEY_FORMAT =
                new DecimalFormat("#,##0.00", symbols);

        MONEY_FORMAT.setGroupingUsed(true);
    }

    // Aktuelles Geld
    private static double balance = -1;

    // Ziel
    private static double goal = -1;

    // Standard: alle 30 Sekunden
    private static int intervalSeconds = 30;

    // Tick-Zähler
    private static long ticksUntilCheck = 0;

    // Verhindert mehrfaches Goal-Signal
    private static boolean goalReachedNotified = false;


    @Override
    public void onInitializeClient() {

        loadConfig();

        /*
         * Chat-Nachrichten überwachen
         */
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) ->
                        parseMoneyMessage(message.getString())
        );

        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) ->
                        parseMoneyMessage(message.getString())
        );


        /*
         * Automatischer /money Check
         */
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (client.player == null || client.level == null) {
                return;
            }

            if (ticksUntilCheck > 0) {
                ticksUntilCheck--;
            }

            /*
             * Zeit für neuen Check
             */
            if (ticksUntilCheck <= 0) {

                client.player.connection.sendCommand("money");

                ticksUntilCheck =
                        intervalSeconds * 20L;
            }


            /*
             * Goal erreicht?
             */
            if (goal > 0
                    && balance >= goal
                    && !goalReachedNotified) {

                goalReachedNotified = true;

                client.player.displayClientMessage(
                        Component.literal(
                                "§a[HugoSMP Money] §6Goal erreicht! §f"
                                        + formatMoney(balance)
                        ),
                        false
                );
            }
        });


        /*
         * Eigene Client Commands
         */
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> {

                    /*
                     * /moneygoal
                     */
                    dispatcher.register(
                            ClientCommands.literal("moneygoal")
                                    .executes(context -> {

                                        showStatus(
                                                context.getSource()
                                        );

                                        return 1;
                                    })

                                    /*
                                     * /moneygoal off
                                     */
                                    .then(
                                            ClientCommands.literal("off")
                                                    .executes(context -> {

                                                        goal = -1;

                                                        goalReachedNotified =
                                                                false;

                                                        saveConfig();

                                                        context.getSource()
                                                                .sendFeedback(
                                                                        Component.literal(
                                                                                "§a[HugoSMP Money] §cMoney-Goal deaktiviert."
                                                                        )
                                                                );

                                                        return 1;
                                                    })
                                    )

                                    /*
                                     * /moneygoal 25000000
                                     */
                                    .then(
                                            ClientCommands.argument(
                                                    "amount",
                                                    StringArgumentType.word()
                                            ).executes(context -> {

                                                String raw =
                                                        StringArgumentType
                                                                .getString(
                                                                        context,
                                                                        "amount"
                                                                );

                                                try {

                                                    double newGoal =
                                                            parseUserAmount(raw);

                                                    if (newGoal <= 0) {
                                                        throw new NumberFormatException();
                                                    }

                                                    goal = newGoal;

                                                    goalReachedNotified =
                                                            balance >= goal;

                                                    saveConfig();

                                                    context.getSource()
                                                            .sendFeedback(
                                                                    Component.literal(
                                                                            "§a[HugoSMP Money] §6Goal: §f"
                                                                                    + formatMoney(goal)
                                                                    )
                                                            );

                                                    return 1;

                                                } catch (NumberFormatException e) {

                                                    context.getSource()
                                                            .sendFeedback(
                                                                    Component.literal(
                                                                            "§cUngültiger Betrag!"
                                                                                    + " Beispiel: /moneygoal 25000000"
                                                                    )
                                                            );

                                                    return 0;
                                                }
                                            })
                                    )
                    );


                    /*
                     * /moneyinterval
                     */
                    dispatcher.register(
                            ClientCommands.literal("moneyinterval")
                                    .executes(context -> {

                                        context.getSource()
                                                .sendFeedback(
                                                        Component.literal(
                                                                "§a[HugoSMP Money] "
                                                                        + "§eIntervall: §f"
                                                                        + intervalSeconds
                                                                        + " Sekunden"
                                                        )
                                                );

                                        return 1;
                                    })

                                    /*
                                     * /moneyinterval 60
                                     */
                                    .then(
                                            ClientCommands.argument(
                                                    "seconds",
                                                    IntegerArgumentType
                                                            .integer(5, 3600)
                                            ).executes(context -> {

                                                int seconds =
                                                        IntegerArgumentType
                                                                .getInteger(
                                                                        context,
                                                                        "seconds"
                                                                );

                                                intervalSeconds =
                                                        seconds;

                                                /*
                                                 * Sofort neuen Check machen
                                                 */
                                                ticksUntilCheck = 0;

                                                saveConfig();

                                                context.getSource()
                                                        .sendFeedback(
                                                                Component.literal(
                                                                        "§a[HugoSMP Money] "
                                                                                + "§eIntervall gesetzt: §f"
                                                                                + seconds
                                                                                + " Sekunden"
                                                                )
                                                        );

                                                return 1;
                                            })
                                    )
                    );
                }
        );


        /*
         * HUD (HudRenderCallback ist veraltet -> HudElementRegistry)
         */
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath(MOD_ID, "money_hud"),
                (graphics, deltaTracker) ->
                        renderHud(graphics)
        );
    }


    /*
     * HugoSMP Kontostand aus Chat lesen
     */
    private static void parseMoneyMessage(String message) {

        if (message == null || message.isBlank()) {
            return;
        }

        // Manche Server-Nachrichten enthalten noch klassische §-Formatcodes.
        String cleanMessage = message.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();

        Matcher matcher =
                MONEY_PATTERN.matcher(cleanMessage);

        if (!matcher.find()) {
            return;
        }

        try {

            /*
             * 19.178.164 -> 19178164
             */
            String whole =
                    matcher.group(1)
                            .replace(".", "");

            /*
             * ,60 -> .60
             */
            String cents =
                    matcher.group(2);

            if (cents == null) {
                cents = "00";
            }

            balance =
                    Double.parseDouble(
                            whole + "." + cents
                    );

        } catch (NumberFormatException ignored) {
        }
    }


    /*
     * Eingabe für Goal umwandeln
     */
    private static double parseUserAmount(String input) {

        return Double.parseDouble(
                input
                        .replace(".", "")
                        .replace(",", ".")
        );
    }


    /*
     * Geld schön formatieren
     */
    private static String formatMoney(double amount) {

        synchronized (MONEY_FORMAT) {

            return "$" +
                    MONEY_FORMAT.format(amount);
        }
    }


    /*
     * HUD zeichnen
     */
    private static void renderHud(
            GuiGraphicsExtractor graphics
    ) {

        if (balance < 0) {
            return;
        }

        Minecraft client =
                Minecraft.getInstance();

        int screenWidth =
                client.getWindow()
                        .getGuiScaledWidth();

        int x =
                screenWidth - 210;

        int y = 8;


        /*
         * Geld
         * (Emojis entfernt: Minecraft-Standardschrift kann sie nicht darstellen)
         */
        graphics.text(
                client.font,

                Component.literal(
                        "§aGeld: §f"
                                + formatMoney(balance)
                ),

                x,
                y,
                WHITE,
                true
        );


        /*
         * Goal
         */
        if (goal > 0) {

            double progress =
                    Math.max(
                            0,
                            Math.min(
                                    1,
                                    balance / goal
                            )
                    );


            graphics.text(
                    client.font,

                    Component.literal(
                            "§6Ziel: §f"
                                    + formatMoney(goal)
                    ),

                    x,
                    y + 12,
                    WHITE,
                    true
            );


            graphics.text(
                    client.font,

                    Component.literal(
                            String.format(
                                    Locale.GERMANY,
                                    "§7Fortschritt: §f%.1f%%",
                                    progress * 100
                            )
                    ),

                    x,
                    y + 24,
                    WHITE,
                    true
            );
        }
    }


    /*
     * /moneygoal ohne Argument
     */
    private static void showStatus(
            FabricClientCommandSource source
    ) {

        String current;

        if (balance < 0) {
            current = "§7noch nicht bekannt";
        } else {
            current = "§f" +
                    formatMoney(balance);
        }


        String target;

        if (goal < 0) {
            target = "§7deaktiviert";
        } else {
            target = "§f" +
                    formatMoney(goal);
        }


        source.sendFeedback(
                Component.literal(
                        "§a[HugoSMP Money] "
                                + "§fGeld: "
                                + current
                                + " §7| §fGoal: "
                                + target
                                + " §7| §fIntervall: "
                                + intervalSeconds
                                + "s"
                )
        );
    }


    /*
     * Config laden
     */
    private static void loadConfig() {

        if (!Files.exists(CONFIG)) {
            return;
        }

        try {

            JsonObject json =
                    GSON.fromJson(
                            Files.readString(CONFIG),
                            JsonObject.class
                    );


            if (json.has("intervalSeconds")) {

                intervalSeconds =
                        Math.max(
                                5,
                                Math.min(
                                        3600,
                                        json.get(
                                                "intervalSeconds"
                                        ).getAsInt()
                                )
                        );
            }


            if (json.has("goal")) {

                goal =
                        json.get("goal")
                                .getAsDouble();
            }

        } catch (Exception ignored) {
        }
    }


    /*
     * Config speichern
     */
    private static void saveConfig() {

        try {

            JsonObject json =
                    new JsonObject();

            json.addProperty(
                    "intervalSeconds",
                    intervalSeconds
            );

            json.addProperty(
                    "goal",
                    goal
            );

            Files.writeString(
                    CONFIG,
                    GSON.toJson(json)
            );

        } catch (IOException ignored) {
        }
    }
}
