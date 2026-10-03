package lab.opticore.vpn;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class OptiCoreShellService
        extends IOptiCoreShellService.Stub {

    public OptiCoreShellService() {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public int getUid() {
        return Process.myUid();
    }

    @Override
    public int getPid() {
        return Process.myPid();
    }

    @Override
    public String getIdentity() {

        return "uid="
                + Process.myUid()
                + ", pid="
                + Process.myPid();
    }

    @Override
    public String readMemInfo() {

        return readTextFile(
                "/proc/meminfo",
                40
        );
    }

    @Override
    public String readNetworkInfo() {

        return readTextFile(
                "/proc/net/dev",
                40
        );
    }

    private static String readTextFile(
            String path,
            int maxLines) {

        StringBuilder out =
                new StringBuilder();

        File file =
                new File(path);

        if (!file.exists()) {
            return "FILE_NOT_FOUND: "
                    + path;
        }

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new FileReader(file)
                        )
        ) {

            String line;
            int count = 0;

            while ((line = reader.readLine()) != null
                    && count < maxLines) {

                out.append(line)
                        .append('\n');

                count++;
            }

        } catch (Throwable e) {

            return e.getClass().getSimpleName()
                    + ": "
                    + (
                    e.getMessage() == null
                            ? ""
                            : e.getMessage()
            );
        }

        return out.toString().trim();
    }
}
