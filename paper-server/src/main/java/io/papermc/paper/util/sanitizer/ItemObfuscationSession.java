package io.papermc.paper.util.sanitizer;

import java.util.function.UnaryOperator;
import com.google.common.base.Preconditions;
import io.papermc.paper.util.SafeAutoClosable;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The item obfuscation session may be started by a thread to indicate that items should be obfuscated when serialized
 * for network usage.
 * <p>
 * A session is persistent throughout an entire thread and will be "activated" by passing an {@link ObfuscationContext}
 * to start/switch context methods.
 */
@NullMarked
public class ItemObfuscationSession implements SafeAutoClosable {

    static final ThreadLocal<ItemObfuscationSession> THREAD_LOCAL_SESSION = ThreadLocal.withInitial(ItemObfuscationSession::new);

    public static ItemObfuscationSession currentSession() {
        return THREAD_LOCAL_SESSION.get();
    }

    /**
     * Obfuscation level on a specific context.
     */
    public enum ObfuscationLevel {
        NONE,
        OVERSIZED,
        ALL;

        public boolean obfuscateOversized() {
            return switch (this) {
                case OVERSIZED, ALL -> true;
                default -> false;
            };
        }

        public boolean isObfuscating() {
            return this != NONE;
        }
    }

    public static ItemObfuscationSession start(final ObfuscationLevel level) {
        final ItemObfuscationSession sanitizer = THREAD_LOCAL_SESSION.get();
        // Papo - reuse a per-level cached start context instead of allocating one per call:
        // the cached contexts are field-identical to a fresh
        // new ObfuscationContext(sanitizer, null, null, level), and context identity is never
        // observed (the only identity check, withContext's newContext != session.context(),
        // always holds because the withers construct fresh instances).
        sanitizer.switchContext(sanitizer.startContexts[level.ordinal()]);
        return sanitizer;
    }

    // Papo start - cached start contexts, one per ObfuscationLevel (see start())
    private final ObfuscationContext[] startContexts = new ObfuscationContext[]{
        new ObfuscationContext(this, null, null, ObfuscationLevel.NONE),
        new ObfuscationContext(this, null, null, ObfuscationLevel.OVERSIZED),
        new ObfuscationContext(this, null, null, ObfuscationLevel.ALL),
    };
    // Papo end

    /**
     * Updates the context of the currently running session by requiring the unary operator to emit a new context
     * based on the current one.
     * The method expects the caller to use the withers on the context.
     *
     * @param contextUpdater the operator to construct the new context.
     * @return the context callback to close once the context expires.
     */
    public static SafeAutoClosable withContext(final UnaryOperator<ObfuscationContext> contextUpdater) {
        final ItemObfuscationSession session = THREAD_LOCAL_SESSION.get();

        // Don't pass any context if we are not currently sanitizing
        if (!session.obfuscationLevel().isObfuscating()) return () -> {
        };

        final ObfuscationContext newContext = contextUpdater.apply(session.context());
        Preconditions.checkState(newContext != session.context(), "withContext yielded same context instance, this will break the stack on close");
        session.switchContext(newContext);
        return newContext;
    }

    // Papo start - direct, allocation-free equivalent of withContext(c -> c.itemStack(itemStack)):
    // context.itemStack(x) always constructs a fresh ObfuscationContext (so the checkState
    // invariant holds identically) and the switch/return sequence is the same.
    /**
     * Switches the current session's context to one carrying the given item stack.
     * Allocation-free equivalent of {@code withContext(c -> c.itemStack(itemStack))}.
     *
     * @param itemStack the item stack to carry as context.
     * @return the context callback to close once the context expires.
     */
    public static SafeAutoClosable withItemStack(final ItemStack itemStack) {
        final ItemObfuscationSession session = THREAD_LOCAL_SESSION.get();

        // Don't pass any context if we are not currently sanitizing
        if (!session.obfuscationLevel().isObfuscating()) return () -> {
        };

        final ObfuscationContext newContext = session.context().itemStack(itemStack);
        Preconditions.checkState(newContext != session.context(), "withItemStack yielded same context instance, this will break the stack on close");
        session.switchContext(newContext);
        return newContext;
    }
    // Papo end

    private final ObfuscationContext root = new ObfuscationContext(this, null, null, ObfuscationLevel.NONE);
    private ObfuscationContext context = root;

    public void switchContext(final ObfuscationContext context) {
        this.context = context;
    }

    public ObfuscationContext context() {
        return this.context;
    }

    @Override
    public void close() {
        this.context = root;
    }

    public ObfuscationLevel obfuscationLevel() {
        return this.context.level;
    }

    public record ObfuscationContext(
        ItemObfuscationSession parent,
        @Nullable ObfuscationContext previousContext,
        @Nullable ItemStack itemStack,
        ObfuscationLevel level
    ) implements SafeAutoClosable {

        public ObfuscationContext itemStack(final ItemStack itemStack) {
            return new ObfuscationContext(this.parent, this, itemStack, this.level);
        }

        public ObfuscationContext level(final ObfuscationLevel obfuscationLevel) {
            return new ObfuscationContext(this.parent, this, this.itemStack, obfuscationLevel);
        }

        @Override
        public void close() {
            // Restore the previous context when this context is closed.
            this.parent().switchContext(this.previousContext);
        }
    }

}
