import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;

public class Bot extends TelegramLongPollingBot {

    // ====== Данные товаров ======
    private final Map<String, Integer> mirrors = Map.of(
            "Маорі", 249,
            "Веста", 249,
            "Мейв", 249,
            "Орнамент", 249,
            "Пафос", 249,
            "Стеліо", 249,
            "Шане", 249
    );

    // ====== Пути к фотографиям в resources ======
    private final Map<String, String> mirrorPhotos = new HashMap<>() {{
        put("Маорі", "/temp_Маорі.jpg");
        put("Веста", "/temp_Веста.jpg");
        put("Мейв", "/temp_Мейв.jpg");
        put("Орнамент", "/temp_Орнамент.jpg");
        put("Пафос", "/temp_Пафос.jpg");
        put("Стеліо", "/temp_Стеліо.jpg");
        put("Шане", "/temp_Шане.jpg");
    }};

    // ====== Пользователи ======
    private final Map<Long, String> userLang = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Integer>> userCart = new ConcurrentHashMap<>();
    private final Map<String, File> cachedImages = new ConcurrentHashMap<>();
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();
    private final DecimalFormat priceFormat = new DecimalFormat("#,###");

    @Override
    public String getBotUsername() {
        return "ForSklobot";
    }

    @Override
    public String getBotToken() {
        return "ВАШ_ТОКЕН_БОТА"; // <-- вставь сюда свой токен
    }

    public static void main(String[] args) {
        try {
            System.setProperty("file.encoding", "UTF-8");
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new Bot());
            System.out.println("✅ Bot started");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====== Получение обновлений ======
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update);
                return;
            }

            if (!update.hasMessage() || !update.getMessage().hasText()) return;

            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText().trim();

            switch (text) {
                case "/start" -> sendLanguageChoice(chatId);
                case "🏠 На головну", "🏠 Home" -> sendMainMenu(chatId);
                case "📸 Каталог", "🛒 Каталог", "📸 Catalog" -> sendCatalog(chatId);
                case "🛒 Кошик", "🛍 Кошик", "🛒 Cart" -> showCart(chatId);
                case "ℹ️ Про нас", "ℹ️ About us" -> sendMessage(chatId, getLang(chatId).equals("EN") ? "ℹ️ About us: ..." : "ℹ️ Про нас: ...");
                case "📞 Контакти", "📞 Contacts" -> sendMessage(chatId, getLang(chatId).equals("EN") ? "📞 Contacts: ..." : "📞 Контакти: ...");
                default -> {
                    if (!userLang.containsKey(chatId)) sendLanguageChoice(chatId);
                    else sendMessage(chatId, getLang(chatId).equals("EN") ? "🤔 Unknown command. Use menu below 👇" : "🤔 Я не розумію команду. Використайте меню нижче 👇", mainMenuMarkup(chatId));
                }
            }

        } catch (Exception e) {
            logToFile("Error in onUpdateReceived", e);
        }
    }

    // ====== Обработка кнопок ======
    private void handleCallback(Update update) {
        try {
            var cb = update.getCallbackQuery();
            long chatId = cb.getMessage().getChatId();
            String data = cb.getData();

            // Выбор языка
            if ("lang_ua".equals(data)) {
                userLang.put(chatId, "UA");
                sendMainMenu(chatId);
                return;
            } else if ("lang_en".equals(data)) {
                userLang.put(chatId, "EN");
                sendMainMenu(chatId);
                return;
            }

            switch (data) {
                case "catalog" -> sendCatalog(chatId);
                case "cart" -> showCart(chatId);
                case "home" -> sendMainMenu(chatId);
                case "about" -> sendMessage(chatId, getLang(chatId).equals("EN") ? "ℹ️ About us: ..." : "ℹ️ Про нас: ...");
                case "contacts" -> sendMessage(chatId, getLang(chatId).equals("EN") ? "📞 Contacts: ..." : "📞 Контакти: ...");
                default -> {
                    if (data.startsWith("choose_")) {
                        String item = data.substring("choose_".length());
                        addToCart(chatId, item, 1);
                        sendMessage(chatId, escapeHtml(item) + (getLang(chatId).equals("EN") ? " added to cart!" : " додано до кошика!"), mainMenuMarkup(chatId));
                    } else if (data.startsWith("plus_")) {
                        String item = data.substring("plus_".length());
                        addToCart(chatId, item, 1);
                        showCart(chatId);
                    } else if (data.startsWith("minus_")) {
                        String item = data.substring("minus_".length());
                        removeFromCart(chatId, item, 1);
                        showCart(chatId);
                    } else if ("clear_cart".equals(data)) {
                        clearCart(chatId);
                        sendMessage(chatId, getLang(chatId).equals("EN") ? "🗑️ Cart cleared!" : "🗑️ Кошик очищено!", mainMenuMarkup(chatId));
                    } else if ("order".equals(data)) {
                        confirmOrder(chatId);
                    }
                }
            }

        } catch (Exception e) {
            logToFile("Error in handleCallback", e);
        }
    }

    // ====== Главное меню ======
    private void sendMainMenu(long chatId) {
        sendMessage(chatId, getLang(chatId).equals("EN") ? "Select an option below 👇" : "Оберіть опцію нижче 👇", mainMenuMarkup(chatId));
    }

    private InlineKeyboardMarkup mainMenuMarkup(long chatId) {
        String lang = getLang(chatId);

        InlineKeyboardButton contacts = new InlineKeyboardButton(lang.equals("EN") ? "📞 Contacts" : "📞 Контакти");
        contacts.setCallbackData("contacts");

        InlineKeyboardButton about = new InlineKeyboardButton(lang.equals("EN") ? "📄 About us" : "📄 Про нас");
        about.setCallbackData("about");

        InlineKeyboardButton catalog = new InlineKeyboardButton(lang.equals("EN") ? "📸 Catalog" : "📸 Каталог");
        catalog.setCallbackData("catalog");

        InlineKeyboardButton cart = new InlineKeyboardButton(lang.equals("EN") ? "🛒 Cart" : "🛒 Кошик");
        cart.setCallbackData("cart");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(contacts, about));
        rows.add(List.of(catalog, cart));

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(rows);
        return kb;
    }

    // ====== Выбор языка ======
    private void sendLanguageChoice(long chatId) {
        InlineKeyboardButton ua = new InlineKeyboardButton("🇺🇦 Українська");
        ua.setCallbackData("lang_ua");
        InlineKeyboardButton en = new InlineKeyboardButton("🇬🇧 English");
        en.setCallbackData("lang_en");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(ua, en)));
        sendMessage(chatId, "🌍 <b>Оберіть мову / Choose language:</b>", kb);
    }

    private String getLang(long chatId) {
        return userLang.getOrDefault(chatId, "EN");
    }

    // ====== Каталог ======
    private void sendCatalog(long chatId) {
        String lang = getLang(chatId);
        sendMessage(chatId, lang.equals("EN") ? "⏳ Loading catalog..." : "⏳ Завантаження каталогу...");

        for (String name : mirrors.keySet()) {
            photoExecutor.submit(() -> {
                try {
                    File file = getCachedImage(name);
                    if (file == null || !file.exists()) {
                        sendMessage(chatId, "⚠️ Фото не знайдено: " + name);
                        return;
                    }

                    int price = mirrors.get(name);
                    SendPhoto photo = new SendPhoto();
                    photo.setChatId(String.valueOf(chatId));
                    photo.setPhoto(new InputFile(file));
                    photo.setCaption("🪞 " + escapeHtml(name) + "\n💰 " + priceFormat.format(price) + " zł");

                    InlineKeyboardButton add = new InlineKeyboardButton(lang.equals("EN") ? "🛒 Add to cart" : "🛒 Додати у кошик");
                    add.setCallbackData("choose_" + name);

                    InlineKeyboardButton home = new InlineKeyboardButton(lang.equals("EN") ? "🏠 Home" : "🏠 На головну");
                    home.setCallbackData("home");

                    InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
                    kb.setKeyboard(List.of(List.of(add), List.of(home)));
                    photo.setReplyMarkup(kb);

                    execute(photo);
                } catch (Exception e) {
                    logToFile("Error sending photo " + name, e);
                }
            });
        }
    }

    // ====== Работа с ресурсами ======
    private File getCachedImage(String name) {
        try {
            if (cachedImages.containsKey(name)) return cachedImages.get(name);

            String resourcePath = mirrorPhotos.get(name);
            if (resourcePath == null) return null;

            InputStream is = getClass().getResourceAsStream(resourcePath);
            if (is == null) return null;

            File tempFile = File.createTempFile("temp_" + name, ".jpg");
            tempFile.deleteOnExit();
            try (OutputStream os = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
            cachedImages.put(name, tempFile);
            return tempFile;

        } catch (Exception e) {
            logToFile("Error caching image " + name, e);
            return null;
        }
    }

    // ====== Корзина ======
    private void addToCart(long chatId, String item, int amount) {
        userCart.computeIfAbsent(chatId, id -> new ConcurrentHashMap<>());
        Map<String, Integer> cart = userCart.get(chatId);
        cart.put(item, cart.getOrDefault(item, 0) + amount);
    }

    private void removeFromCart(long chatId, String item, int amount) {
        Map<String, Integer> cart = userCart.getOrDefault(chatId, new HashMap<>());
        if (!cart.containsKey(item)) return;
        int cur = cart.get(item);
        if (cur <= amount) cart.remove(item);
        else cart.put(item, cur - amount);
    }

    private void clearCart(long chatId) {
        userCart.remove(chatId);
    }

    private void showCart(long chatId) {
        Map<String, Integer> cart = userCart.getOrDefault(chatId, Collections.emptyMap());
        String lang = getLang(chatId);

        if (cart.isEmpty()) {
            sendMessage(chatId, lang.equals("EN") ? "🛒 Your cart is empty" : "🛒 Ваш кошик порожній", mainMenuMarkup(chatId));
            return;
        }

        StringBuilder sb = new StringBuilder(lang.equals("EN") ? "🛒 <b>Your cart:</b>\n\n" : "🛒 <b>Ваш кошик:</b>\n\n");
        int total = 0;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (var entry : cart.entrySet()) {
            String item = entry.getKey();
            int qty = entry.getValue();
            int price = mirrors.getOrDefault(item, 0);
            total += price * qty;
            sb.append("• ").append(escapeHtml(item)).append(" x").append(qty).append(" — ").append(priceFormat.format(price * qty)).append(" zł\n");

            InlineKeyboardButton minus = new InlineKeyboardButton("➖");
            minus.setCallbackData("minus_" + item);
            InlineKeyboardButton plus = new InlineKeyboardButton("➕");
            plus.setCallbackData("plus_" + item);
            rows.add(List.of(minus, plus));
        }

        sb.append("\n💰 <b>").append(lang.equals("EN") ? "Total: " : "Разом: ").append(priceFormat.format(total)).append(" zł</b>");

        InlineKeyboardButton order = new InlineKeyboardButton(lang.equals("EN") ? "✅ Order" : "✅ Замовити");
        order.setCallbackData("order");
        InlineKeyboardButton clear = new InlineKeyboardButton(lang.equals("EN") ? "🗑️ Clear cart" : "🗑️ Очистити кошик");
        clear.setCallbackData("clear_cart");
        InlineKeyboardButton home = new InlineKeyboardButton(lang.equals("EN") ? "🏠 Home" : "🏠 На головну");
        home.setCallbackData("home");

        rows.add(List.of(order));
        rows.add(List.of(clear, home));

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(rows);
        sendMessage(chatId, sb.toString(), kb);
    }

    private void confirmOrder(long chatId) {
        Map<String, Integer> cart = userCart.getOrDefault(chatId, Collections.emptyMap());
        String lang = getLang(chatId);
        if (cart.isEmpty()) {
            sendMessage(chatId, lang.equals("EN") ? "🛒 Cart is empty!" : "🛒 Кошик порожній!");
            return;
        }
        sendMessage(chatId, lang.equals("EN") ? "✅ Your order has been received! We will contact you soon" : "✅ Ваше замовлення прийнято! Незабаром ми з вами зв'яжемося");
        userCart.remove(chatId);
    }

    private void sendMessage(long chatId, String text, InlineKeyboardMarkup kb) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        msg.setParseMode("HTML");
        if (kb != null) msg.setReplyMarkup(kb);
        try { execute(msg); } catch (TelegramApiException e) { logToFile("Error sending message", e); }
    }

    private void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    private void logToFile(String message, Exception e) {
        System.err.println(message);
        if (e != null) e.printStackTrace();
    }

    private static String sanitizeFilename(String s) {
        return s.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
