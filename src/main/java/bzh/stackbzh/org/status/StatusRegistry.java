package bzh.stackbzh.org.status;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class StatusRegistry {

    public record ComponentInfo(String name, ComponentState state, String detail, long updatedAtMillis) {
    }

    public record DownloadInfo(String name, long downloadedBytes, long totalBytes, boolean done) {
    }

    private final long startedAtMillis = System.currentTimeMillis();
    private final Map<String, ComponentInfo> components = new ConcurrentHashMap<>();
    private final List<String> componentOrder = new CopyOnWriteArrayList<>();
    private final Map<String, DownloadInfo> downloads = new ConcurrentHashMap<>();
    private final List<String> downloadOrder = new CopyOnWriteArrayList<>();

    public void setComponent(String name, ComponentState state, String detail) {
        components.put(name, new ComponentInfo(name, state, detail, System.currentTimeMillis()));
        if (!componentOrder.contains(name)) {
            componentOrder.add(name);
        }
    }

    public void updateDownload(String name, long downloadedBytes, long totalBytes, boolean done) {
        downloads.put(name, new DownloadInfo(name, downloadedBytes, totalBytes, done));
        if (!downloadOrder.contains(name)) {
            downloadOrder.add(name);
        }
    }

    public List<ComponentInfo> components() {
        return componentOrder.stream().map(components::get).filter(Objects::nonNull).toList();
    }

    public List<DownloadInfo> downloads() {
        return downloadOrder.stream().map(downloads::get).filter(Objects::nonNull).toList();
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }
}
