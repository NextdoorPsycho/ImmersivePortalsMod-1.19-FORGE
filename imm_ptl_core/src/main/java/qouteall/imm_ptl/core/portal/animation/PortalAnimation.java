package qouteall.imm_ptl.core.portal.animation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.PortalState;
import qouteall.q_misc_util.Helper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class PortalAnimation {
    /**
     * The default animation is triggered when changing portal
     * The default animation is client-only. On the server side it changes abruptly.
     * The default animation cannot do relative teleportation.
     */
    @NotNull
    public DefaultPortalAnimation defaultAnimation = DefaultPortalAnimation.createDefault();
    
    /**
     * The animation driver controls the real animation.
     * The real animation runs on both sides and can do relative teleportation.
     */
    @NotNull
    public List<PortalAnimationDriver> thisSideAnimations = new ArrayList<>();
    @NotNull
    public List<PortalAnimationDriver> otherSideAnimations = new ArrayList<>();
    
    public long pauseTime = 0;
    public long timeOffset = 0;
    
    @Nullable
    public UnilateralPortalState thisSideReferenceState;
    @Nullable
    public UnilateralPortalState otherSideReferenceState;
    
    @Nullable
    public PortalState lastTickAnimatedState;
    @Nullable
    public PortalState thisTickAnimatedState;
    
    // for client player teleportation
    @OnlyIn(Dist.CLIENT)
    @Nullable
    public PortalState clientLastFramePortalState;
    @OnlyIn(Dist.CLIENT)
    public long clientLastFramePortalStateCounter = -1;
    @OnlyIn(Dist.CLIENT)
    @Nullable
    public PortalState clientCurrentFramePortalState;
    @OnlyIn(Dist.CLIENT)
    public long clientCurrentFramePortalStateCounter = -1;
    
    public void readFromTag(CompoundTag tag) {
        if (tag.contains("animation")) {
            defaultAnimation = DefaultPortalAnimation.fromNbt(tag.getCompound("animation"));
        }
        else if (tag.contains("defaultAnimation")) {
            defaultAnimation = DefaultPortalAnimation.fromNbt(tag.getCompound("defaultAnimation"));
        }
        else {
            defaultAnimation = DefaultPortalAnimation.createDefault();
        }
        
        if (tag.contains("thisSideAnimations")) {
            ListTag listTag = tag.getList("thisSideAnimations", 10);
            thisSideAnimations = Helper.listTagToList(listTag, PortalAnimationDriver::fromTag);
        }
        else {
            thisSideAnimations.clear();
        }
        
        if (tag.contains("otherSideAnimations")) {
            ListTag listTag = tag.getList("otherSideAnimations", 10);
            otherSideAnimations = Helper.listTagToList(listTag, PortalAnimationDriver::fromTag);
        }
        else {
            otherSideAnimations.clear();
        }
        
        if (tag.contains("pauseTime")) {
            pauseTime = tag.getLong("pauseTime");
        }
        else {
            pauseTime = 0;
        }
        
        if (tag.contains("timeOffset")) {
            timeOffset = tag.getLong("timeOffset");
        }
        else {
            timeOffset = 0;
        }
        
        if (tag.contains("thisSideReferenceState")) {
            thisSideReferenceState = UnilateralPortalState.fromTag(tag.getCompound("thisSideReferenceState"));
        }
        else {
            thisSideReferenceState = null;
        }
        
        if (tag.contains("otherSideReferenceState")) {
            otherSideReferenceState = UnilateralPortalState.fromTag(tag.getCompound("otherSideReferenceState"));
        }
        else {
            otherSideReferenceState = null;
        }
    }
    
    public void writeToTag(CompoundTag tag) {
        tag.put("defaultAnimation", defaultAnimation.toNbt());
        
        if (!thisSideAnimations.isEmpty()) {
            tag.put(
                "thisSideAnimations",
                Helper.listToListTag(thisSideAnimations, PortalAnimationDriver::toTag)
            );
        }
        
        if (!otherSideAnimations.isEmpty()) {
            tag.put(
                "otherSideAnimations",
                Helper.listToListTag(otherSideAnimations, PortalAnimationDriver::toTag)
            );
        }
        
        if (pauseTime != 0) {
            tag.putLong("pauseTime", pauseTime);
        }
        if (timeOffset != 0) {
            tag.putLong("timeOffset", timeOffset);
        }
        
        if (thisSideReferenceState != null) {
            tag.put("thisSideReferenceState", thisSideReferenceState.toTag());
        }
        if (otherSideReferenceState != null) {
            tag.put("otherSideReferenceState", otherSideReferenceState.toTag());
        }
    }
    
    public boolean isRoughlyRunningAnimation() {
        return lastTickAnimatedState != null || thisTickAnimatedState != null || hasRunningAnimationDriver();
    }
    
    public boolean hasRunningAnimationDriver() {
        return !isPaused() && (!thisSideAnimations.isEmpty() || !otherSideAnimations.isEmpty());
    }
    
    public boolean isPaused() {
        return pauseTime != 0;
    }
    
    public void setPaused(Portal portal, boolean paused) {
        if (paused == isPaused()) {
            return;
        }
        
        if (paused) {
            pauseTime = portal.level.getGameTime();
        }
        else {
            timeOffset -= portal.level.getGameTime() - pauseTime;
            pauseTime = 0;
        }
    }
    
    public long getEffectiveTime(long gameTime) {
        return (isPaused() ? pauseTime : gameTime) + timeOffset;
    }
    
    public void tick(Portal portal) {
        lastTickAnimatedState = thisTickAnimatedState;
        thisTickAnimatedState = null;
        
        if (!portal.level.isClientSide()) {
            /*
              The server ticking process {@link ServerLevel#tick(BooleanSupplier)}:
              1. increase game time
              2. tick entities (including portals)
              So use 1 as partial tick
             */
            updateAnimationDriver(
                portal, portal.animation, portal.level.getGameTime(), 1, true, true
            );
    
            if (thisSideAnimations.isEmpty()) {
                thisSideReferenceState = null;
            }
            if (otherSideAnimations.isEmpty()) {
                otherSideReferenceState = null;
            }
        }
        else {
            // handled on client
            if (hasRunningAnimationDriver()) {
                markRequiresClientAnimationUpdate(portal);
            }
        }
    }
    
    @OnlyIn(Dist.CLIENT)
    private static void markRequiresClientAnimationUpdate(Portal portal) {
        ClientPortalAnimationManagement.markRequiresCustomAnimationUpdate(portal);
    }
    
    public void updateAnimationDriver(
        Portal portal,
        PortalAnimation animation,
        long gameTime,
        float partialTicks,
        boolean isTicking,
        boolean canRemoveAnimation
    ) {
        if (!hasRunningAnimationDriver()) {
            return;
        }
        
        PortalState portalState = portal.getPortalState();
        if (portalState == null) {
            return;
        }
        
        long effectiveGameTime = animation.getEffectiveTime(gameTime);
        float effectivePartialTicks = animation.isPaused() ? 0 : partialTicks;
    
        initializeReferenceStates(portalState);
    
        UnilateralPortalState.Builder thisSideState = new UnilateralPortalState.Builder().from(thisSideReferenceState);
        UnilateralPortalState.Builder otherSideState = new UnilateralPortalState.Builder().from(otherSideReferenceState);
        
        int originalThisSideAnimationCount = thisSideAnimations.size();
        int originalOtherSideAnimationCount = otherSideAnimations.size();
        
        thisSideAnimations.removeIf(animationDriver -> {
            boolean finished = animationDriver.update(thisSideState, effectiveGameTime, effectivePartialTicks);
            return canRemoveAnimation && finished;
        });
        
        otherSideAnimations.removeIf(animationDriver -> {
            boolean finished = animationDriver.update(otherSideState, effectiveGameTime, effectivePartialTicks);
            return canRemoveAnimation && finished;
        });
        
        if (thisSideState.dimension != portalState.fromWorld || otherSideState.dimension != portalState.toWorld) {
            Helper.err("Portal animation driver cannot change dimension");
            portal.clearAnimationDrivers(true, true);
            return;
        }
        
        UnilateralPortalState newThisSideState = thisSideState.build();
        UnilateralPortalState newOtherSideState = otherSideState.build();
        PortalState newPortalState =
            UnilateralPortalState.combine(newThisSideState, newOtherSideState);
        portal.setPortalState(newPortalState);
        
        if (isTicking) {
            portal.animation.thisTickAnimatedState = newPortalState;
        }
        
        portal.rectifyClusterPortals(false);
        if (isTicking) {
            PortalExtension extension = PortalExtension.get(portal);
            updateThisTickAnimatedState(extension.flippedPortal);
            updateThisTickAnimatedState(extension.reversePortal);
            updateThisTickAnimatedState(extension.parallelPortal);
        }
        
        if (!portal.level.isClientSide()) {
            if (thisSideAnimations.size() != originalThisSideAnimationCount ||
                otherSideAnimations.size() != originalOtherSideAnimationCount
            ) {
                // delay a little to make client animation stopping smoother
                PortalExtension.forClusterPortals(portal, p -> p.reloadAndSyncToClientWithTickDelay(1));
            }
        }
    }
    
    private void initializeReferenceStates(PortalState portalState) {
        if (thisSideReferenceState == null) {
            thisSideReferenceState = UnilateralPortalState.extractThisSide(portalState);
        }
        if (otherSideReferenceState == null) {
            otherSideReferenceState = UnilateralPortalState.extractOtherSide(portalState);
        }
    }
    
    public void addThisSideAnimationDriver(Portal portal, PortalAnimationDriver animationDriver) {
        PortalExtension extension = PortalExtension.get(portal);
        if (extension.flippedPortal != null) {
            if (extension.flippedPortal.animation.hasRunningAnimationDriver()) {
                extension.flippedPortal.animation.thisSideAnimations.add(animationDriver.getFlippedVersion());
                extension.flippedPortal.reloadAndSyncToClientNextTick();
                return;
            }
        }
        
        if (extension.reversePortal != null) {
            if (extension.reversePortal.animation.hasRunningAnimationDriver()) {
                extension.reversePortal.animation.otherSideAnimations.add(animationDriver);
                extension.reversePortal.reloadAndSyncToClientNextTick();
                return;
            }
        }
        
        if (extension.parallelPortal != null) {
            if (extension.parallelPortal.animation.hasRunningAnimationDriver()) {
                extension.parallelPortal.animation.otherSideAnimations.add(animationDriver.getFlippedVersion());
                extension.parallelPortal.reloadAndSyncToClientNextTick();
                return;
            }
        }
        
        thisSideAnimations.add(animationDriver);
        portal.reloadAndSyncToClientNextTick();
    }
    
    public void addOtherSideAnimationDriver(Portal portal, PortalAnimationDriver animationDriver) {
        PortalExtension extension = PortalExtension.get(portal);
        if (extension.flippedPortal != null) {
            if (extension.flippedPortal.animation.hasRunningAnimationDriver()) {
                extension.flippedPortal.animation.otherSideAnimations.add(animationDriver.getFlippedVersion());
                extension.flippedPortal.reloadAndSyncToClientNextTick();
                return;
            }
        }
        
        if (extension.reversePortal != null) {
            if (extension.reversePortal.animation.hasRunningAnimationDriver()) {
                extension.reversePortal.animation.thisSideAnimations.add(animationDriver);
                extension.reversePortal.reloadAndSyncToClientNextTick();
                return;
            }
        }
        
        if (extension.parallelPortal != null) {
            if (extension.parallelPortal.animation.hasRunningAnimationDriver()) {
                extension.parallelPortal.animation.thisSideAnimations.add(animationDriver.getFlippedVersion());
                extension.parallelPortal.reloadAndSyncToClientNextTick();
                return;
            }
        }
        
        otherSideAnimations.add(animationDriver);
        portal.reloadAndSyncToClientNextTick();
    }
    
    public void clearAnimationDrivers(Portal portal, boolean clearThisSide, boolean clearOtherSide) {
        Validate.isTrue(!portal.level.isClientSide());
        
        if (thisSideAnimations.isEmpty() && otherSideAnimations.isEmpty()) {
            return;
        }
        
        PortalState portalState = portal.getPortalState();
        assert portalState != null;
        UnilateralPortalState.Builder from = new UnilateralPortalState.Builder()
            .from(UnilateralPortalState.extractThisSide(portalState));
        UnilateralPortalState.Builder to = new UnilateralPortalState.Builder()
            .from(UnilateralPortalState.extractOtherSide(portalState));
        
        if (clearThisSide) {
            for (PortalAnimationDriver animationDriver : thisSideAnimations) {
                animationDriver.obtainEndingState(from, portal.level.getGameTime());
            }
            thisSideAnimations.clear();
            thisSideReferenceState = null;
        }
        
        if (clearOtherSide) {
            for (PortalAnimationDriver animationDriver : otherSideAnimations) {
                animationDriver.obtainEndingState(to, portal.level.getGameTime());
            }
            otherSideAnimations.clear();
            otherSideReferenceState = null;
        }
        
        PortalState newState = UnilateralPortalState.combine(from.build(), to.build());
        portal.setPortalState(newState);
    }
    
    public PortalState getAnimationEndingState(Portal portal) {
        PortalState portalState = portal.getPortalState();
        assert portalState != null;
        
        initializeReferenceStates(portalState);
        assert thisSideReferenceState != null;
        assert otherSideReferenceState != null;
        
        UnilateralPortalState.Builder from = new UnilateralPortalState.Builder()
            .from(thisSideReferenceState);
        UnilateralPortalState.Builder to = new UnilateralPortalState.Builder()
            .from(otherSideReferenceState);
        
        for (PortalAnimationDriver animationDriver : thisSideAnimations) {
            animationDriver.obtainEndingState(from, portal.level.getGameTime());
        }
        
        for (PortalAnimationDriver animationDriver : otherSideAnimations) {
            animationDriver.obtainEndingState(to, portal.level.getGameTime());
        }
        
        return UnilateralPortalState.combine(from.build(), to.build());
    }
    
    @OnlyIn(Dist.CLIENT)
    public void updateClientState(Portal portal, long currentTeleportationCounter) {
        if (currentTeleportationCounter == clientCurrentFramePortalStateCounter) {
            return;
        }
        
        if (isRoughlyRunningAnimation()) {
            clientLastFramePortalState = clientCurrentFramePortalState;
            clientLastFramePortalStateCounter = clientCurrentFramePortalStateCounter;
            
            clientCurrentFramePortalState = portal.getPortalState();
            clientCurrentFramePortalStateCounter = currentTeleportationCounter;
        }
    }
    
    static void updateThisTickAnimatedState(Portal secondaryPortal) {
        if (secondaryPortal != null) {
            if (secondaryPortal.animation.thisTickAnimatedState != null) {
                Helper.log("Conflicting animation in " + secondaryPortal);
                secondaryPortal.clearAnimationDrivers(true, true);
            }
            secondaryPortal.animation.thisTickAnimatedState = secondaryPortal.getPortalState();
        }
    }
}
