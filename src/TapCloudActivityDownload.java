import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TapCloudActivityDownload {

    private static final String ADB =
            "C:\\Users\\oleh\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";

    private static final String DEVICE = "emulator-5554";
    private static final String REMOTE_XML = "/sdcard/window_dump.xml";

    private static final int SWIPE_X = 620;
    private static final int SWIPE_START_Y = 1860;
    private static final int SWIPE_END_Y = 1660;
    private static final int SWIPE_DURATION_MS = 900;
    private static final int FALLBACK_CLOSE_DOWNLOAD_SCREEN_X = 72;
    private static final int FALLBACK_CLOSE_DOWNLOAD_SCREEN_Y = 315;

    private static final long WAIT_AFTER_SWIPE_MS = 1400;
    private static final long WAIT_AFTER_MENU_OPEN_MS = 450;
    private static final long WAIT_AFTER_DOWNLOAD_TAP_MS = 1000;
    private static final long WAIT_AFTER_DUMP_MS = 250;

    private static final int MAX_EMPTY_SCROLLS = 5;

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        Set<String> seenCardIds = new LinkedHashSet<>();
        int emptyScrolls = 0;

        log("Started Cloud Activity downloader");

        while (true) {
            DumpResult dump = dumpAndParse();
            List<VideoCard> freshCards = new ArrayList<>();

            for (VideoCard card : dump.cards) {
                if (seenCardIds.add(card.uniqueId)) {
                    freshCards.add(card);
                }
            }

            log("Visible cards: " + dump.cards.size()
                    + ", fresh cards: " + freshCards.size()
                    + ", total seen: " + seenCardIds.size());

            for (VideoCard card : freshCards) {
                log("Downloading " + card.uniqueId);

                tap(card.menuX, card.menuY);
                Thread.sleep(WAIT_AFTER_MENU_OPEN_MS);

                boolean tapped = tapDownloadFromOpenedMenu(card);
                if (!tapped) {
                    int[] fallback = card.downloadFallbackPoint();
                    log("Download button not found in XML, fallback tap: "
                            + fallback[0] + "," + fallback[1]);
                    tap(fallback[0], fallback[1]);
                }

                Thread.sleep(WAIT_AFTER_DOWNLOAD_TAP_MS);
            }

            if (freshCards.isEmpty()) {
                emptyScrolls++;
                log("No new cards after scroll attempt " + emptyScrolls + "/" + MAX_EMPTY_SCROLLS);
            } else {
                emptyScrolls = 0;
            }

            if (emptyScrolls >= MAX_EMPTY_SCROLLS) {
                break;
            }

            slowSwipeListUp();
            Thread.sleep(WAIT_AFTER_SWIPE_MS);
            closeDownloadScreenIfOpened();
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        log("Finished. Total unique cards: " + seenCardIds.size() + ", elapsed ms: " + elapsedMs);
    }

    private static DumpResult dumpAndParse() throws Exception {
        Document doc = dumpCurrentWindow();
        NodeList nodes = doc.getElementsByTagName("node");
        List<VideoCard> cards = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String resourceId = el.getAttribute("resource-id");

            if (!"com.tplink.iot:id/message_info_time".equals(resourceId)) {
                continue;
            }

            String time = safe(el.getAttribute("text"));
            if (!isVideoTime(time)) {
                continue;
            }

            Element cardRoot = findCardRoot(el);
            if (cardRoot == null) {
                continue;
            }

            int[] cardBounds = parseBounds(cardRoot.getAttribute("bounds"));
            if (cardBounds == null) {
                continue;
            }

            Element menuNode = findDescendantById(cardRoot, "com.tplink.iot:id/img_item_more");
            int[] menuPoint = menuNode != null
                    ? center(menuNode.getAttribute("bounds"))
                    : defaultMenuPoint(cardBounds, el);

            CardTexts texts = collectCardTexts(cardRoot, time);
            String uniqueId = normalize(texts.title) + "|" + time + "|" + normalize(texts.camera);

            cards.add(new VideoCard(
                    uniqueId,
                    time,
                    texts.title,
                    texts.camera,
                    menuPoint[0],
                    menuPoint[1],
                    cardBounds
            ));
        }

        return new DumpResult(cards);
    }

    private static Document dumpCurrentWindow() throws Exception {
        run(ADB, "-s", DEVICE, "shell", "uiautomator", "dump", REMOTE_XML);
        Thread.sleep(WAIT_AFTER_DUMP_MS);

        String xml = run(ADB, "-s", DEVICE, "shell", "cat", REMOTE_XML);
        int start = xml.indexOf("<?xml");
        if (start >= 0) {
            xml = xml.substring(start);
        }

        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
    }

    private static boolean tapDownloadFromOpenedMenu(VideoCard card) throws Exception {
        Document doc = dumpCurrentWindow();
        NodeList nodes = doc.getElementsByTagName("node");

        Element best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);

            String resourceId = safe(el.getAttribute("resource-id"));
            String text = safe(el.getAttribute("text"));
            String contentDesc = safe(el.getAttribute("content-desc"));
            String bounds = safe(el.getAttribute("bounds"));
            if (bounds.isEmpty()) {
                continue;
            }

            boolean looksLikeDownload =
                    "com.tplink.iot:id/iv_download".equals(resourceId)
                            || containsIgnoreCase(text, "download")
                            || containsIgnoreCase(contentDesc, "download");

            if (!looksLikeDownload) {
                continue;
            }

            int[] point = center(bounds);
            int score = 0;

            if ("com.tplink.iot:id/iv_download".equals(resourceId)) {
                score += 1000;
            }

            if (point[1] >= card.cardBounds[1] && point[1] <= card.cardBounds[3]) {
                score += 300;
            }

            score -= Math.abs(point[1] - card.menuY);
            score -= Math.abs(point[0] - card.menuX) / 3;

            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }

        if (best == null) {
            return false;
        }

        int[] point = center(best.getAttribute("bounds"));
        log("Tap download by XML: " + point[0] + "," + point[1]);
        tap(point[0], point[1]);
        return true;
    }

    private static CardTexts collectCardTexts(Element cardRoot, String time) {
        NodeList nodes = cardRoot.getElementsByTagName("node");
        List<TextItem> texts = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String text = safe(el.getAttribute("text"));
            if (text.isEmpty()) {
                continue;
            }

            if (isVideoTime(text) || isDuration(text) || isDateLabel(text) || isStatusText(text)) {
                continue;
            }

            int[] point = center(el.getAttribute("bounds"));
            texts.add(new TextItem(text, point[0], point[1]));
        }

        String camera = "";
        String title = "";

        for (TextItem item : texts) {
            if (camera.isEmpty() && looksLikeCameraName(item.text)) {
                camera = item.text;
                continue;
            }

            if (title.isEmpty()) {
                title = item.text;
            }
        }

        if (title.isEmpty()) {
            title = "video";
        }

        if (camera.isEmpty()) {
            camera = title;
        }

        return new CardTexts(title, camera, time);
    }

    private static boolean looksLikeCameraName(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("tapo")
                || lower.contains("cam")
                || lower.contains("camera")
                || text.contains("_");
    }

    private static boolean isDuration(String text) {
        return text.matches("\\d{2}:\\d{2}");
    }

    private static boolean isDateLabel(String text) {
        return text.matches("\\d{1,2}/\\d{1,2}/\\d{2,4}");
    }

    private static boolean isStatusText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("download complete")
                || lower.contains("cloud activity")
                || lower.contains("supports displaying");
    }

    private static Element findCardRoot(Element timeNode) {
        Element current = timeNode;

        while (current != null) {
            String resourceId = safe(current.getAttribute("resource-id"));
            if ("com.tplink.iot:id/cl_single_video_root".equals(resourceId)
                    || "com.tplink.iot:id/layout_single_video_1".equals(resourceId)
                    || "com.tplink.iot:id/cl_regular".equals(resourceId)) {
                return current;
            }

            current = parentElement(current);
        }

        return parentElement(parentElement(timeNode));
    }

    private static Element findDescendantById(Element root, String wantedId) {
        NodeList nodes = root.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (wantedId.equals(el.getAttribute("resource-id"))) {
                return el;
            }
        }
        return null;
    }

    private static int[] defaultMenuPoint(int[] cardBounds, Element timeNode) {
        int[] timePoint = center(timeNode.getAttribute("bounds"));
        return new int[] {
                cardBounds[2] - 72,
                timePoint[1]
        };
    }

    private static Element parentElement(Node node) {
        Node parent = node == null ? null : node.getParentNode();
        return parent instanceof Element ? (Element) parent : null;
    }

    private static boolean containsIgnoreCase(String s, String part) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    private static String normalize(String value) {
        String text = safe(value);
        return text.isEmpty() ? "-" : text.toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static void tap(int x, int y) throws Exception {
        run(
                ADB, "-s", DEVICE, "shell", "input", "tap",
                String.valueOf(x),
                String.valueOf(y)
        );
    }

    private static boolean isVideoTime(String text) {
        return text != null && text.matches("\\d{2}:\\d{2}:\\d{2}");
    }

    private static void slowSwipeListUp() throws Exception {
        log("Swipe up slowly: x=" + SWIPE_X
                + ", startY=" + SWIPE_START_Y
                + ", endY=" + SWIPE_END_Y
                + ", durationMs=" + SWIPE_DURATION_MS);
        run(
                ADB, "-s", DEVICE, "shell", "input", "swipe",
                String.valueOf(SWIPE_X),
                String.valueOf(SWIPE_START_Y),
                String.valueOf(SWIPE_X),
                String.valueOf(SWIPE_END_Y),
                String.valueOf(SWIPE_DURATION_MS)
        );
    }

    private static void closeDownloadScreenIfOpened() throws Exception {
        Document doc = dumpCurrentWindow();
        if (!isDownloadScreen(doc)) {
            return;
        }

        log("Download screen opened after swipe, closing it");

        if (tapDownloadScreenCloseByXml(doc)) {
            Thread.sleep(300);
            return;
        }

        log("Close button not found in XML, fallback tap: "
                + FALLBACK_CLOSE_DOWNLOAD_SCREEN_X + ","
                + FALLBACK_CLOSE_DOWNLOAD_SCREEN_Y);
        tap(FALLBACK_CLOSE_DOWNLOAD_SCREEN_X, FALLBACK_CLOSE_DOWNLOAD_SCREEN_Y);
        Thread.sleep(300);
    }

    private static boolean isDownloadScreen(Document doc) {
        NodeList nodes = doc.getElementsByTagName("node");
        boolean hasDownloadTitle = false;
        boolean hasCompleteStatus = false;

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String text = safe(el.getAttribute("text"));
            int[] point = center(el.getAttribute("bounds"));

            if ("Download".equalsIgnoreCase(text) && point[1] > 150 && point[1] < 650) {
                hasDownloadTitle = true;
            }

            if (containsIgnoreCase(text, "complete")) {
                hasCompleteStatus = true;
            }
        }

        return hasDownloadTitle || (hasDownloadTitle && hasCompleteStatus);
    }

    private static boolean tapDownloadScreenCloseByXml(Document doc) throws Exception {
        NodeList nodes = doc.getElementsByTagName("node");
        Element best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String text = safe(el.getAttribute("text"));
            String contentDesc = safe(el.getAttribute("content-desc"));
            String clickable = safe(el.getAttribute("clickable"));
            int[] rect = parseBounds(el.getAttribute("bounds"));
            if (rect == null) {
                continue;
            }

            int centerX = (rect[0] + rect[2]) / 2;
            int centerY = (rect[1] + rect[3]) / 2;
            int width = rect[2] - rect[0];
            int height = rect[3] - rect[1];

            boolean topLeftCandidate =
                    "true".equals(clickable)
                            && centerX < 220
                            && centerY > 150
                            && centerY < 650
                            && width <= 180
                            && height <= 180;

            boolean explicitClose =
                    containsIgnoreCase(contentDesc, "close")
                            || containsIgnoreCase(contentDesc, "back")
                            || containsIgnoreCase(text, "close")
                            || "x".equalsIgnoreCase(text)
                            || "×".equals(text);

            if (!topLeftCandidate && !explicitClose) {
                continue;
            }

            int score = 0;
            if (explicitClose) {
                score += 1000;
            }
            if (topLeftCandidate) {
                score += 500;
            }

            score -= Math.abs(centerX - FALLBACK_CLOSE_DOWNLOAD_SCREEN_X);
            score -= Math.abs(centerY - FALLBACK_CLOSE_DOWNLOAD_SCREEN_Y);

            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }

        if (best == null) {
            return false;
        }

        int[] point = center(best.getAttribute("bounds"));
        log("Close Download screen by XML: " + point[0] + "," + point[1]);
        tap(point[0], point[1]);
        return true;
    }

    private static int[] parseBounds(String bounds) {
        if (safe(bounds).isEmpty()) {
            return null;
        }

        String normalized = bounds.replace("[", "").replace("]", ",");
        String[] parts = normalized.split(",");
        if (parts.length < 4) {
            return null;
        }

        return new int[] {
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])
        };
    }

    private static int[] center(String bounds) {
        int[] rect = parseBounds(bounds);
        if (rect == null) {
            return new int[] {0, 0};
        }

        return new int[] {
                (rect[0] + rect[2]) / 2,
                (rect[1] + rect[3]) / 2
        };
    }

    private static void log(String message) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        System.out.println("[" + ts + "] " + message);
    }

    private static String run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process p = pb.start();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            is.transferTo(baos);
        }

        int exit = p.waitFor();
        String output = baos.toString();

        if (exit != 0) {
            throw new RuntimeException(
                    "Command failed: " + String.join(" ", cmd) + "\n" + output
            );
        }

        return output;
    }

    private static class TextItem {
        final String text;
        final int x;
        final int y;

        TextItem(String text, int x, int y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    private static class CardTexts {
        final String title;
        final String camera;
        final String time;

        CardTexts(String title, String camera, String time) {
            this.title = title;
            this.camera = camera;
            this.time = time;
        }
    }

    private static class VideoCard {
        final String uniqueId;
        final String time;
        final String title;
        final String camera;
        final int menuX;
        final int menuY;
        final int[] cardBounds;

        VideoCard(String uniqueId, String time, String title, String camera,
                  int menuX, int menuY, int[] cardBounds) {
            this.uniqueId = uniqueId;
            this.time = time;
            this.title = title;
            this.camera = camera;
            this.menuX = menuX;
            this.menuY = menuY;
            this.cardBounds = cardBounds;
        }

        int[] downloadFallbackPoint() {
            int width = cardBounds[2] - cardBounds[0];
            int x = cardBounds[0] + (width * 2 / 3);
            int y = cardBounds[1] + ((cardBounds[3] - cardBounds[1]) / 2);
            return new int[] {x, y};
        }
    }

    private static class DumpResult {
        final List<VideoCard> cards;

        DumpResult(List<VideoCard> cards) {
            this.cards = cards;
        }
    }
}
