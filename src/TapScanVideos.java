import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class TapScanVideos {

    private static final String ADB =
            "C:\\Users\\ubern\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";

    private static final String DEVICE = "emulator-5554";
    private static final String REMOTE_XML = "/sdcard/window_dump.xml";

    private static final int SWIPE_X = 672;
    private static final int SWIPE_START_Y = 2550;
    private static final int SWIPE_END_Y = 2050;
    private static final int SWIPE_DURATION_MS = 1000;

    private static final long WAIT_AFTER_SWIPE_MS = 2000;

    public static void main(String[] args) throws Exception {

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

            // загрузка новых карточек
            for (VideoCard card : newCards) {

                System.out.println("Download: " + card.time);

                tap(card.menuX, card.menuY);

                Thread.sleep(500);

                tapDownload();

                Thread.sleep(1000);
            }

            if (newCards.isEmpty()) {
                noNewDataInRow++;
            } else {
                noNewDataInRow = 0;
            }

            if (noNewDataInRow >= 2) {
                break;
            }

            slowSwipeListUp();

            Thread.sleep(WAIT_AFTER_SWIPE_MS);
        }

        System.out.println("Finished. Total: " + allUniqueVideoIds.size());
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

                    // кнопка меню справа
                    int menuX = 1200;
                    int menuY = xy[1];

                    cards.add(new VideoCard(text, menuX, menuY));
                }
            }
        }

        return new DumpResult(cards);
    }

    private static void tapDownload() throws Exception {

        DumpResult dump = dumpAndParse();

        for (VideoCard card : dump.cards) {
            // ищем Download
        }

        // координаты кнопки Download (пример)
        tap(600, 2600);
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

        return new int[]{
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

        if (exit != 0) {
            throw new RuntimeException("Command failed");
        }

        return baos.toString();
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