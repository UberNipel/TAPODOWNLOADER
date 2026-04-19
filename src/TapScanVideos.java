import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TapScanVideos {

    private static final String ADB =
            "C:\\Users\\oleh\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";

    private static final String DEVICE = "emulator-5554";
    private static final String REMOTE_XML = "/sdcard/window_dump.xml";

    private static final int SWIPE_X = 672;
    private static final int SWIPE_START_Y = 2550;
    private static final int SWIPE_END_Y = 2050;
    private static final int SWIPE_DURATION_MS = 1000;

    private static final long WAIT_AFTER_SWIPE_MS = 500;
    private static final long WAIT_AFTER_MENU_OPEN_MS = 250;
    private static final long WAIT_AFTER_DOWNLOAD_TAP_MS = 250;

    // fallback: верхний пункт нижнего меню
    // 2600 у тебя бил в Delete, значит Download должен быть заметно выше
    private static final int FALLBACK_DOWNLOAD_X = 672;
    private static final int FALLBACK_DOWNLOAD_Y = 2360;
    public static final int RETRY_COUNT = 5;

    public static void main(String[] args) throws Exception {

        long l = System.nanoTime();
        Set<String> allUniqueVideoIds = new LinkedHashSet<>();

        int noNewDataInRow = 0;

        while (true) {
            DumpResult dump = dumpAndParse();

            List<VideoCard> newCards = new ArrayList<>();

            for (VideoCard card : dump.cards) {
                if (!allUniqueVideoIds.contains(card.time)) {
                    newCards.add(card);
                    allUniqueVideoIds.add(card.time);
                }
            }

            System.out.println("New cards: " + newCards.size()
                    + " total: " + allUniqueVideoIds.size());

            for (VideoCard card : newCards) {
                System.out.println("Download: " + card.time);

                tap(card.menuX, card.menuY);
                Thread.sleep(WAIT_AFTER_MENU_OPEN_MS);

                boolean tappedDownload = tapDownloadFromOpenedMenu();

                if (!tappedDownload) {
                    System.out.println("Download option not found in XML, fallback tap used.");
                    tap(FALLBACK_DOWNLOAD_X, FALLBACK_DOWNLOAD_Y);
                }

                Thread.sleep(WAIT_AFTER_DOWNLOAD_TAP_MS);
            }

            if (newCards.isEmpty()) {
                noNewDataInRow++;
            } else {
                noNewDataInRow = 0;
            }

            if (noNewDataInRow >= RETRY_COUNT) {
                break;
            }

            slowSwipeListUp();
            Thread.sleep(WAIT_AFTER_SWIPE_MS);
        }

        System.out.println("Finished. Total: " + allUniqueVideoIds.size());

        System.out.println("Time " + (System.nanoTime() - l));
    }

    private static DumpResult dumpAndParse() throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        String localFile = "dump_" + ts + ".xml";

        run(ADB, "-s", DEVICE, "shell", "uiautomator", "dump", REMOTE_XML);
        run(ADB, "-s", DEVICE, "pull", REMOTE_XML, localFile);

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(localFile));

        NodeList nodes = doc.getElementsByTagName("node");
        List<VideoCard> cards = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String id = el.getAttribute("resource-id");

            if ("com.tplink.iot:id/message_info_time".equals(id)) {
                String text = el.getAttribute("text");

                if (isVideoTime(text)) {
                    String bounds = el.getAttribute("bounds");
                    int[] xy = center(bounds);

                    // В твоих дампах кнопка More живёт справа от карточки:
                    // resource-id="com.tplink.iot:id/img_item_more"
                    // bounds примерно [1146,...][1290,...]
                    int menuX = 1218;
                    int menuY = xy[1];

                    cards.add(new VideoCard(text, menuX, menuY));
                }
            }
        }

        return new DumpResult(cards);
    }

    private static boolean tapDownloadFromOpenedMenu() throws Exception {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        String localFile = "menu_" + ts + ".xml";

        run(ADB, "-s", DEVICE, "shell", "uiautomator", "dump", REMOTE_XML);
        run(ADB, "-s", DEVICE, "pull", REMOTE_XML, localFile);

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(localFile));

        NodeList nodes = doc.getElementsByTagName("node");

        Element bestCandidate = null;
        int bestY = Integer.MAX_VALUE;

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);

            String text = safe(el.getAttribute("text"));
            String contentDesc = safe(el.getAttribute("content-desc"));
            String bounds = safe(el.getAttribute("bounds"));

            if (bounds.isEmpty()) {
                continue;
            }

            int[] xy = center(bounds);
            int centerY = xy[1];

            // игнорируем верхнюю вкладку Download в хедере
            if (centerY < 1800) {
                continue;
            }

            boolean looksLikeDownload =
                    containsIgnoreCase(text, "download") ||
                            containsIgnoreCase(contentDesc, "download");

            if (looksLikeDownload) {
                if (centerY < bestY) {
                    bestY = centerY;
                    bestCandidate = el;
                }
            }
        }

        if (bestCandidate != null) {
            int[] xy = center(bestCandidate.getAttribute("bounds"));
            System.out.println("Tap Download by XML: " + xy[0] + "," + xy[1]);
            tap(xy[0], xy[1]);
            return true;
        }

        return false;
    }

    private static boolean containsIgnoreCase(String s, String part) {
        return s != null && s.toLowerCase().contains(part.toLowerCase());
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
        run(
                ADB, "-s", DEVICE, "shell", "input", "swipe",
                String.valueOf(SWIPE_X),
                String.valueOf(SWIPE_START_Y),
                String.valueOf(SWIPE_X),
                String.valueOf(SWIPE_END_Y),
                String.valueOf(SWIPE_DURATION_MS)
        );
    }

    private static int[] center(String bounds) {
        bounds = bounds.replace("[", "").replace("]", ",");
        String[] parts = bounds.split(",");

        int x1 = Integer.parseInt(parts[0]);
        int y1 = Integer.parseInt(parts[1]);
        int x2 = Integer.parseInt(parts[2]);
        int y2 = Integer.parseInt(parts[3]);

        return new int[] {
                (x1 + x2) / 2,
                (y1 + y2) / 2
        };
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

    private static class VideoCard {
        final String time;
        final int menuX;
        final int menuY;

        VideoCard(String time, int menuX, int menuY) {
            this.time = time;
            this.menuX = menuX;
            this.menuY = menuY;
        }
    }

    private static class DumpResult {
        final List<VideoCard> cards;

        DumpResult(List<VideoCard> cards) {
            this.cards = cards;
        }
    }
}
