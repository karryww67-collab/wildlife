package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.repository.ImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 原图可用性探测 —— 判断 {@code recognition_image.file_path} 指向的物理文件是否还在。
 *
 * <h3>为什么要单独做一层</h3>
 * {@code file_path} 存的是绝对路径，物理文件落在 bind mount（{@code ./ai-engine/data}）上。
 * 该目录一旦被清理（删数据腾空间、重建 data、换机器部署），库里就会留下一批
 * <b>有行无文件</b>的悬空引用：{@code /api/images/{id}/raw} 返回 404，
 * 人工复核弹窗看不到图 —— 而看不到图就等于没有判据，不该允许提交复核。
 *
 * <h3>为什么不用 SQL 直接过滤</h3>
 * 「文件是否存在」是<b>文件系统事实</b>，不在数据库里，没法写进 {@code WHERE} 参与
 * {@code LIMIT/OFFSET} 分页；在 Java 里"取一页再筛掉几条"又会让每页条数忽多忽少、页码错位。
 * 所以这里把这份事实<b>物化成一个 ID 集合</b>：SQL 侧就能用
 * {@code image_id NOT IN (...)} 过滤、用 {@code COUNT(*)} 计数，分页因此仍然是数据库分页。
 *
 * <h3>缓存与规模</h3>
 * 探测一次要遍历全表做 {@code Files.exists()}。本机实测约
 * <b>0.47 ms/行</b>（bind mount 上 {@code stat} 很贵）：564 行 0.28 s，
 * 10^4 行约 4.7 s，<b>10^5 行约 47 s</b>。
 * <p>为此做了两件事：一是 {@value #TTL_MS} ms 的 TTL 缓存，二是
 * {@link #scheduledRefresh()} 在后台提前续期，让这个开销不落在用户请求线程上。
 * <p><b>但这只是把开销挪走，没有消除它。</b>到 10^5 级时，每 {@code TTL_MS/2} ms
 * 就要跑一次 47 s 的探测，后台线程会持续饱和 —— 届时正确做法是在
 * {@code recognition_image} 上落一列 {@code file_missing}，由巡检任务
 * （{@code ai-engine/scripts/check_image_consistency.py}）定期回写，本类退化成纯读库。
 * 只有 {@link #refresh()} 的实现要换，调用方不动。
 */
@Service
public class ImageAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(ImageAvailabilityService.class);

    /** 缓存有效期；到期后下一次访问会重新全量探测。 */
    private static final long TTL_MS = 60_000L;

    private final ImageRepository imageRepository;

    /** 物理文件已丢失的图像 ID 快照；{@code null} 表示尚未探测过。 */
    private volatile Set<Long> missingIds;
    private volatile long loadedAt;

    public ImageAvailabilityService(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    /**
     * 物理文件已丢失的图像 ID 快照（必要时先刷新）。
     *
     * <p>返回值只读：{@link #refresh()} 每次替换整个集合引用，调用方不必担心并发修改。
     */
    public Set<Long> missingImageIds() {
        ensureFresh();
        Set<Long> current = missingIds;
        return current == null ? Set.of() : current;
    }

    /** 某张图像的原图是否可读；{@code null} 视为"未知即不拦"（例如无图的结果）。 */
    public boolean isAvailable(Long imageId) {
        return imageId == null || !missingImageIds().contains(imageId);
    }

    /**
     * 强制重新探测一次，并替换缓存。
     *
     * <p>删除图像、清理磁盘文件、恢复备份之后可以调它让口径立刻跟上；
     * 正常路径不需要手工调用（TTL 到期自动刷新）。
     *
     * @return 本次探测到的缺失图像 ID 集合
     */
    public synchronized Set<Long> refresh() {
        List<RecognitionImage> images = imageRepository.selectList(
                new QueryWrapper<RecognitionImage>().select("id", "file_path"));

        Set<Long> missing = new HashSet<>();
        for (RecognitionImage image : images) {
            String path = image.getFilePath();
            if (path == null || path.isBlank() || !exists(path)) {
                missing.add(image.getId());
            }
        }

        missingIds = missing;
        loadedAt = System.currentTimeMillis();
        log.info("原图可用性探测: 共 {} 行, 物理文件缺失 {} 行", images.size(), missing.size());
        return missing;
    }

    /**
     * 后台定期刷新，把全量探测挪出请求线程。
     *
     * <p>{@link #ensureFresh()} 仍保留"过期就同步补一次"的兜底，但正常路径下不会走到：
     * 这里的刷新周期取 {@code TTL_MS / 2}，也就是在快照过期之前就提前续期，
     * 于是用户请求几乎总是读到新鲜快照、不会阻塞。缓存失效只可能发生在
     * 调度线程被拖住或首次探测尚未完成时，那时 {@code ensureFresh()} 兜底保证口径正确。
     *
     * <p>刷新周期之所以用 {@code fixedDelay} 而不是 {@code fixedRate}：探测本身可能是秒级，
     * {@code fixedDelay} 表示"上一次跑完再等这么久"，不会让任务重叠堆积；
     * 而且 {@code refresh()} 是 {@code synchronized} 的，重叠也没有意义。
     *
     * <p>异常在这里被吞掉是刻意的：一次探测失败不该影响后续调度，
     * 沿用上一次快照比让复核队列整页打不开要好。
     */
    @Scheduled(initialDelay = TTL_MS / 2, fixedDelay = TTL_MS / 2)
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("原图可用性后台刷新失败，沿用上一次快照", e);
        }
    }

    /**
     * 单次判存。
     *
     * <p>非法路径（含 NUL、超长等）按"不可用"处理：一条脏数据不该让整个探测抛异常，
     * 否则复核队列会整页打不开，损失比误报一行大得多。
     */
    private static boolean exists(String path) {
        try {
            Path file = Paths.get(path);
            return Files.isRegularFile(file);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void ensureFresh() {
        if (missingIds != null && System.currentTimeMillis() - loadedAt < TTL_MS) {
            return;
        }
        refresh();
    }
}
