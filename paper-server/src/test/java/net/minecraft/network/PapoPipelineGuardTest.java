package net.minecraft.network;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - compat guard regression test: shared packet instances and wire memos are only
// served to connections whose pipeline contains exactly the vanilla-installed handlers. This
// pins the fail-closed behavior: any foreign handler class (plugin injector) must flip the
// guard to impure, and a vanilla-shaped pipeline must stay pure.
public class PapoPipelineGuardTest {

    @Test
    void pipelineWithForeignHandlerIsImpure() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(new FlowControlHandler()); // vanilla class, unnamed slot
        Assertions.assertTrue(PapoPipelineGuard.papoPurePipeline(channel.pipeline()), "vanilla-only pipeline must be pure");

        channel.pipeline().addLast("plugin-injector", new ChannelInboundHandlerAdapter());
        Assertions.assertFalse(PapoPipelineGuard.papoPurePipeline(channel.pipeline()), "any foreign handler must flip the guard to impure (fail closed)");
        channel.close();
    }

    @Test
    void subclassedVanillaHandlerIsImpure() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30) {
            // plugin-style subclass of a vanilla handler class
        });
        Assertions.assertFalse(PapoPipelineGuard.papoPurePipeline(channel.pipeline()), "a subclass of a vanilla handler has a different class name and must count as foreign");
        channel.close();
    }

    @Test
    void anonymousAdapterOutsideConnectionIsForeign() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ChannelHandler hackfixLike = new io.netty.channel.ChannelOutboundHandlerAdapter() {};
        // The guard allows anonymous adapters declared by Connection itself (Connection$N prefix).
        // A locally-declared anonymous adapter does NOT carry that prefix and must be foreign.
        final String className = hackfixLike.getClass().getName();
        Assertions.assertFalse(className.startsWith("net.minecraft.network.Connection$"),
            "test fixture sanity: local anonymous adapter must not carry the Connection$ prefix");
        channel.pipeline().addLast("hackfix", hackfixLike);
        Assertions.assertFalse(PapoPipelineGuard.papoPurePipeline(channel.pipeline()), "anonymous adapters not declared by Connection must be foreign");
        channel.close();
    }

    @Test
    void emptyPipelineIsPure() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        Assertions.assertTrue(PapoPipelineGuard.papoPurePipeline(channel.pipeline()));
        channel.close();
    }
}
