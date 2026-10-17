package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class Http2DataSpanTest {
    @ParameterizedTest @ValueSource(ints = {1, 16384, 16385, 32736, 32737, 32768, 32769, 49120, 65408})
    void fragmentsKeepTheirBytesBoundsAndFinalAcknowledgement(int length) throws Exception {
        var coordinator = coordinator(200000, 1);
        byte[] source = new byte[length + 13];
        new Random(576).nextBytes(source);
        var task = new WriteTask(new Http2DataSpan(1, true, source, 7, length), false);
        coordinator.submit(task);
        coordinator.processAvailableCommands();
        var actual = new ByteArrayOutputStream();
        int frames = 0;
        while (actual.size() < length) {
            var next = coordinator.pollWritable();
            assertNotNull(next);
            assertTrue(next.beginWrite());
            var frame = (Http2DataFrame) next.frame();
            assertFalse(frame instanceof Http2DataSpan);
            assertSame(source, frame.payload());
            assertTrue(frame.payloadLength() > 0 && frame.payloadLength() <= 16384);
            actual.write(frame.payload(), frame.payloadOffset(), frame.payloadLength());
            assertEquals(actual.size() == length, frame.endStream());
            assertFalse(task.completion().isDone());
            assertNull(coordinator.pollWritable(), "Only one fragment may own the write at a time");
            next.complete();
            assertEquals(actual.size() == length, task.completion().isDone());
            frames++;
        }
        assertArrayEquals(java.util.Arrays.copyOfRange(source, 7, 7 + length), actual.toByteArray());
        if (length <= 32768) assertEquals((length + 16383) / 16384, frames);
        if (length == 65408) assertEquals(4, frames);
        assertTrue(coordinator.isIdle());
    }

    @Test void threeBusyStreamsTakeTurnsBetweenFragments() {
        var coordinator = coordinator(300000, 1, 3, 5);
        var tasks = new ArrayList<WriteTask>();
        for (int id : new int[]{1, 3, 5}) {
            var task = span(id, true);
            tasks.add(task); coordinator.submit(task);
        }
        coordinator.processAvailableCommands();
        var order = new ArrayList<Integer>();
        for (int i = 0; i < 12; i++) {
            var frame = coordinator.pollWritable(); assertNotNull(frame);
            assertTrue(frame.beginWrite());
            order.add(frame.frame().streamId());
            frame.complete();
        }
        assertEquals(List.of(1, 3, 5, 1, 3, 5, 1, 3, 5, 1, 3, 5), order);
        for (var task : tasks) assertTrue(task.completion().isDone());
        assertTrue(coordinator.isIdle());
    }

    @Test void yieldingKeepsLaterDataAndOrderedResetBehindTheSpan() {
        var coordinator = coordinator(200000, 1, 3);
        var body = span(1, false);
        var tail = new WriteTask(new Http2DataFrame(1, true, new byte[]{42}, 0, 1), false);
        var reset = new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code());
        coordinator.submit(body); coordinator.submit(tail); coordinator.resetAfterPendingWrites(reset);
        coordinator.submit(new WriteTask(new Http2DataFrame(3, true, new byte[]{99}, 0, 1), false));
        coordinator.processAvailableCommands();
        var first = coordinator.pollWritable(); assertNotNull(first); assertTrue(first.beginWrite()); first.complete();
        var other = coordinator.pollWritable(); assertNotNull(other);
        assertEquals(3, other.frame().streamId()); assertTrue(other.beginWrite()); other.complete();
        for (int i = 0; i < 3; i++) {
            var part = coordinator.pollWritable(); assertNotNull(part);
            assertEquals(1, part.frame().streamId()); assertFalse(part.frame().endStream());
            assertFalse(tail.completion().isDone()); assertTrue(part.beginWrite()); part.complete();
        }
        assertTrue(body.completion().isDone());
        var last = coordinator.pollWritable(); assertNotNull(last);
        assertEquals(1, last.frame().flowControlSize());
        assertEquals(42, ((Http2DataFrame) last.frame()).payload()[0]);
        assertTrue(last.frame().endStream()); assertTrue(last.beginWrite()); last.complete();
        var rst = coordinator.pollWritable(); assertNotNull(rst); assertSame(reset, rst.frame());
        assertTrue(rst.beginWrite()); rst.complete();
        assertTrue(coordinator.isIdle());
    }

    @Test void newCreditCannotStartASecondFragmentBeforeAcknowledgement() {
        var coordinator = coordinator(1, 1);
        var task = span(1, true); coordinator.submit(task); coordinator.processAvailableCommands();
        var first = coordinator.pollWritable(); assertNotNull(first); assertTrue(first.beginWrite());
        assertEquals(1, first.frame().flowControlSize());
        coordinator.applyConnectionWindowUpdate(200000, 1);
        coordinator.applyStreamWindowUpdate(1, 200000);
        coordinator.processAvailableCommands();
        assertNull(coordinator.pollWritable());
        var inboundCredit = new Http2WindowUpdate(1, 17);
        coordinator.submit(new WriteTask(inboundCredit, false)); coordinator.processAvailableCommands();
        var update = coordinator.pollWritable(); assertNotNull(update); assertSame(inboundCredit, update.frame());
        assertTrue(update.beginWrite()); update.complete();
        first.complete();
        var next = coordinator.pollWritable(); assertNotNull(next); assertTrue(next.beginWrite());
        assertFalse(task.completion().isDone());
        var failure = new IOException("abort active fragment");
        coordinator.resetStream(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()), failure, null);
        coordinator.processAvailableCommands();
        assertFalse(task.completion().isDone());
        assertNull(coordinator.pollWritable());
        next.complete();
        assertSame(failure, assertThrows(CompletionException.class, task.completion()::join).getCause());
        assertTrue(coordinator.isIdle());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancellationBeforeAndDuringAFragmentNeverResumesTheBorrow(boolean active) {
        var coordinator = coordinator(200000, 1, 3);
        var task = span(1, false); coordinator.submit(task); coordinator.processAvailableCommands();
        var frame = coordinator.pollWritable(); assertNotNull(frame);
        if (active) assertTrue(frame.beginWrite());
        var failure = new IOException("cancelled");
        assertEquals(active, task.cancel(failure));
        if (active) {
            assertFalse(task.completion().isDone()); frame.complete();
        } else assertFalse(frame.beginWrite());
        assertSame(failure, assertThrows(CompletionException.class, task.completion()::join).getCause());
        assertNull(coordinator.pollWritable());
        assertTrue(coordinator.isIdle());
        // Removing a cancelled span must not leave its successors permanently ineligible.
        var next = new WriteTask(new Http2DataFrame(1, true, new byte[]{7}, 0, 1), false);
        coordinator.submit(next); coordinator.processAvailableCommands();
        var writable = coordinator.pollWritable(); assertNotNull(writable);
        assertTrue(writable.beginWrite()); writable.complete(); assertTrue(next.completion().isDone());
    }

    private static WriteTask span(int id, boolean endStream) {
        return new WriteTask(new Http2DataSpan(id, endStream, new byte[65408], 0, 65408), false);
    }

    @Test void aCancelledYieldedSpanDoesNotHideAnAlreadyQueuedSuccessor() {
        var coordinator = coordinator(200000, 1);
        var body = span(1, false);
        var tail = new WriteTask(new Http2DataFrame(1, true, new byte[]{42}, 0, 1), false);
        coordinator.submit(body); coordinator.submit(tail); coordinator.processAvailableCommands();
        var first = coordinator.pollWritable(); assertNotNull(first); assertTrue(first.beginWrite()); first.complete();
        assertFalse(body.cancel(new IOException("cancel between fragments")));
        var next = coordinator.pollWritable();
        assertNotNull(next, "Cancellation must not require another command to expose ready work");
        assertEquals(1, next.frame().flowControlSize());
        assertTrue(next.beginWrite()); next.complete();
        assertNull(coordinator.pollWritable());
        assertTrue(tail.completion().isDone());
        assertTrue(coordinator.isIdle());
    }

    @Test void settingsCanReduceAnActiveSpansWindowBelowZeroAndLaterCreditResumesIt() {
        var coordinator = coordinator(200000, 1);
        var body = span(1, true); coordinator.submit(body); coordinator.processAvailableCommands();
        var first = coordinator.pollWritable(); assertNotNull(first); assertTrue(first.beginWrite()); first.complete();
        var ack = new WriteTask(Http2Settings.ACK, false);
        coordinator.applyPeerSettingsChange(-190000, 4096, ack, 1);
        coordinator.processAvailableCommands();
        var settings = coordinator.pollWritable(); assertNotNull(settings);
        assertSame(Http2Settings.ACK, settings.frame()); assertTrue(settings.beginWrite()); settings.complete();
        assertNull(coordinator.pollWritable()); assertFalse(body.completion().isDone());
        coordinator.applyStreamWindowUpdate(1, 10000); coordinator.processAvailableCommands();
        var small = coordinator.pollWritable(); assertNotNull(small);
        assertEquals(3648, small.frame().flowControlSize());
        assertTrue(small.beginWrite()); small.complete();
        assertNull(coordinator.pollWritable());
        coordinator.applyStreamWindowUpdate(1, 100000); coordinator.processAvailableCommands();
        int turns = 0;
        while (!body.completion().isDone()) {
            assertTrue(++turns < 5);
            var next = coordinator.pollWritable(); assertNotNull(next);
            assertTrue(next.beginWrite()); next.complete();
        }
        assertTrue(coordinator.isIdle());
    }

    private static Http2WriteCoordinator coordinator(int credit, int... ids) {
        var result = new Http2WriteCoordinator(credit);
        for (int id : ids) result.openStream(id, credit);
        result.processAvailableCommands();
        return result;
    }
}
