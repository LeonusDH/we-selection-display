package dev.selviz.render;

import org.spongepowered.api.block.BlockState;

/**
 * Как выглядит линия одного типа.
 *
 * @param block     каким блоком рисовать брусок
 * @param glowColor цвет подсветки 0xRRGGBB или {@code -1}, чтобы оставить ванильный (белый)
 */
public record LineLook(BlockState block, int glowColor) {
}
