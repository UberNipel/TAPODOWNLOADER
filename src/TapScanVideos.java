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
            "C:\\Users\\ubern\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";

    private static final String DEVICE = "emulator-5554";
    private static final String REMOTE_XML = "/sdcard/window_dump.xml";

    // Область списка карточек из твоего XML:
    // recycle_cloud_video_list bounds="[0,1311][1344,2920]"
    // Делаем небольшой медленный свайп примерно на высоту одной карточки
    private static final int SWIPE_X = 672;
    private static final int SWIPE_START_Y = 2550;
    private static final int SWIPE_END_Y = 2050;
    private static final int SWIPE_DURATION_MS = 1000;

    private static final long WAIT_AFTER_SWIPE_MS = 2000;
    private static final long WAIT_BEFORE_CHECK_MS = 1000;

    public static void main(String[] args) throws Exception {
        Set<String> allUniqueVideoIds = new LinkedHashSet<>();

        int iteration = 0;
        int noNewDataInRow = 0;

        while (true) {
            iteration++;

            DumpResult dump = dumpAndParse();
            int before = allUniqueVideoIds.size();

            allUniqueVideoIds.addAll(dump.videoIds);

            int after = allUniqueVideoIds.size();
            int addedNow = after - before;

            System.out.println("Iteration: " + iteration
                    + ", on screen: " + dump.videoIds.size()
                    + ", added new: " + addedNow
                    + ", total unique: " + after);

            if (addedNow > 0) {
                noNewDataInRow = 0;
            } else {
                noNewDataInRow++;
            }

            if (noNewDataInRow >= 2) {
                break;
            }

            slowSwipeListUp();
            Thread.sleep(WAIT_AFTER_SWIPE_MS);
            Thread.sleep(WAIT_BEFORE_CHECK_MS);
        }

        System.out.println();
        System.out.println("=== FINAL RESULT ===");
        System.out.println("Total unique cards: " + allUniqueVideoIds.size());

        int index = 1;
        for (String id : allUniqueVideoIds) {
            System.out.println(index + ". " + id);
            index++;
        }
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

        List<String> currentScreenVideoIds = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);

            String resourceId = el.getAttribute("resource-id");
            String text = el.getAttribute("text");

            if ("com.tplink.iot:id/message_info_time".equals(resourceId) && isVideoTime(text)) {
                currentScreenVideoIds.add(text);
            }
        }

        return new DumpResult(localFile, currentScreenVideoIds);
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

    private static class DumpResult {
        final String localFile;
        final List<String> videoIds;

        DumpResult(String localFile, List<String> videoIds) {
            this.localFile = localFile;
            this.videoIds = videoIds;
        }
    }
}