package com.markagent.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话信箱：enqueue→pending 往返、seq 递增、投递游标语义、并发追加无丢失（临时目录，纯文件 IO） */
class SessionInboxServiceTest {

    @TempDir
    Path tmp;

    private SessionInboxService newService() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new SessionInboxService(mapper, tmp.toAbsolutePath().normalize());
    }

    private static InboxMessage msg(String fromTaskId, String content) {
        return new InboxMessage(0, "u-from", fromTaskId, content, Instant.now());
    }

    @Test
    void enqueueAssignsSeqAndPendingReturnsInOrder() {
        SessionInboxService service = newService();
        long s1 = service.enqueue("u1", "s1", msg("t1", "第一条"));
        long s2 = service.enqueue("u1", "s1", msg("t2", "第二条"));
        assertEquals(1, s1);
        assertEquals(2, s2);

        List<InboxMessage> pending = service.pending("u1", "s1");
        assertEquals(List.of(1L, 2L), pending.stream().map(InboxMessage::seq).toList());
        assertEquals("t1", pending.get(0).fromTaskId());
        assertEquals("u-from", pending.get(0).fromUserId());
        assertEquals("第一条", pending.get(0).content());
        assertTrue(pending.get(0).createdAt() != null);
    }

    @Test
    void seqContinuesAcrossServiceInstances() {
        SessionInboxService first = newService();
        first.enqueue("u1", "s1", msg("t1", "a"));
        first.enqueue("u1", "s1", msg("t1", "b"));
        // 新实例（模拟服务重启）基于文件行数续号
        long s3 = newService().enqueue("u1", "s1", msg("t1", "c"));
        assertEquals(3, s3);
    }

    @Test
    void pendingIsIdempotentUntilMarkedDelivered() {
        SessionInboxService service = newService();
        service.enqueue("u1", "s1", msg("t1", "a"));
        service.enqueue("u1", "s1", msg("t1", "b"));

        // 未 markDelivered：重复 pending 返回同样消息（只读不消费，游标判重保证不重复投递语义）
        List<InboxMessage> first = service.pending("u1", "s1");
        List<InboxMessage> second = service.pending("u1", "s1");
        assertEquals(first.stream().map(InboxMessage::seq).toList(),
                second.stream().map(InboxMessage::seq).toList());

        // 部分投递：游标推进到 1 → 只剩 seq 2
        service.markDelivered("u1", "s1", 1);
        List<InboxMessage> rest = service.pending("u1", "s1");
        assertEquals(List.of(2L), rest.stream().map(InboxMessage::seq).toList());

        // 全部投递：游标推进到 2 → 空；随后用较小值重复 markDelivered 不回退游标
        service.markDelivered("u1", "s1", 2);
        assertTrue(service.pending("u1", "s1").isEmpty());
        service.markDelivered("u1", "s1", 1);
        assertTrue(service.pending("u1", "s1").isEmpty());
    }

    @Test
    void cursorFilePersistsDeliveredSeq() throws Exception {
        SessionInboxService service = newService();
        service.enqueue("u1", "s1", msg("t1", "a"));
        service.markDelivered("u1", "s1", 1);
        Path cursor = tmp.resolve("inbox").resolve("u1").resolve("s1.cursor");
        assertTrue(Files.isRegularFile(cursor));
        assertEquals("1", Files.readString(cursor, StandardCharsets.UTF_8).trim());
        // cursor 不存在视为 0：另一信箱未投递 → pending 全量返回
        service.enqueue("u1", "s2", msg("t1", "b"));
        assertEquals(1, service.pending("u1", "s2").size());
    }

    @Test
    void nullUserNormalizedToLocalUser() {
        SessionInboxService service = newService();
        service.enqueue(null, "s9", msg("t1", "a"));
        assertEquals(1, service.pending(null, "s9").size());
        assertTrue(Files.isRegularFile(tmp.resolve("inbox").resolve("local-user").resolve("s9.jsonl")));
    }

    @Test
    void concurrentEnqueueNoLoss() throws Exception {
        SessionInboxService service = newService();
        int threads = 2;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Callable<List<Long>> job = () -> {
                List<Long> seqs = new ArrayList<>(perThread);
                start.await();
                for (int i = 0; i < perThread; i++) {
                    seqs.add(service.enqueue("u1", "s1", msg("t" + tid, "内容-" + i)));
                }
                return seqs;
            };
            futures.add(pool.submit(job));
        }
        start.countDown();
        Set<Long> seqs = new HashSet<>();
        for (Future<List<Long>> future : futures) {
            seqs.addAll(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 100 条全部成功且 seq 覆盖 1..100 无重复（无丢失）
        assertEquals(threads * perThread, seqs.size());
        for (long i = 1; i <= threads * perThread; i++) {
            assertTrue(seqs.contains(i), "缺失 seq " + i);
        }
        assertEquals(threads * perThread, service.pending("u1", "s1").size());
        assertEquals(threads * perThread, Files.readAllLines(
                tmp.resolve("inbox").resolve("u1").resolve("s1.jsonl"), StandardCharsets.UTF_8).size());
    }
}
