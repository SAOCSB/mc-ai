package io.github.saocsb.mcai.core.placement;

/**
 * Where the requesting player is, in block coordinates.
 *
 * @param groundY world Y used as local y = 0 for {@link GroundMode#PLAYER}: the player's feet block Y
 *                when standing, otherwise the first free Y above the terrain below them
 */
public record PlayerPose(int blockX, int blockY, int blockZ, Facing facing, int groundY) {
}
