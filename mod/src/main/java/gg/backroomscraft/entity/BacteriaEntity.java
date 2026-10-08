package gg.backroomscraft.entity;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Escape the Backrooms' Bacteria as a Minecraft monster. Its look and sound come from the game's files (drawn by the
 * client's CreatureDraw); how it behaves here is this mod's: it wanders, and runs at any player it sees.
 */
public class BacteriaEntity extends Monster {
	public static final Sheets.Creature ROW = Sheets.creature("bacteria");
	public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, BackroomsCraft.id(ROW.id()));
	public static EntityType<BacteriaEntity> TYPE;
	private static final EntityDataAccessor<Boolean> CHASING = SynchedEntityData.defineId(BacteriaEntity.class, EntityDataSerializers.BOOLEAN);

	public BacteriaEntity(EntityType<? extends BacteriaEntity> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
	}

	public static void register() {
		// hook: entity_type
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY, EntityType.Builder.<BacteriaEntity>of(BacteriaEntity::new, MobCategory.MONSTER)
				.sized(ROW.width(), ROW.height()).eyeHeight(ROW.height() * 0.85F).clientTrackingRange(10).noLootTable().build(KEY));
		// hook: entity_attributes
		FabricDefaultAttributeRegistry.register(TYPE, Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, ROW.health())
				.add(Attributes.ATTACK_DAMAGE, ROW.damage()).add(Attributes.MOVEMENT_SPEED, ROW.speed()).add(Attributes.FOLLOW_RANGE, ROW.followRange())
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.8).add(Attributes.STEP_HEIGHT, 1.0));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(CHASING, false);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(2, new ChaseGoal());
		this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.5));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 12.0F));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		boolean chasing = this.getTarget() != null && this.getTarget().isAlive();
		if (chasing != this.entityData.get(CHASING)) {
			this.entityData.set(CHASING, chasing);
		}
	}

	/**
	 * Runs at its target and hits it when in reach. It follows a path when the level's collision gives one; when there
	 * is none but the target is in plain sight, it heads straight for it.
	 */
	private final class ChaseGoal extends Goal {
		private int repath, cooldown;

		ChaseGoal() {
			this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			LivingEntity target = getTarget();
			return target != null && target.isAlive();
		}

		@Override
		public void stop() {
			getNavigation().stop();
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			LivingEntity target = getTarget();
			if (target == null) {
				return;
			}
			getLookControl().setLookAt(target, 30, 30);
			if (--repath <= 0) {
				repath = 8;
				getNavigation().moveTo(target, 1.0);
			}
			if (getNavigation().isDone() && getSensing().hasLineOfSight(target)) {
				getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0);
			}
			cooldown--;
			double reach = ROW.attackReach() + target.getBbWidth() / 2;
			if (cooldown <= 0 && distanceToSqr(target) < reach * reach && level() instanceof ServerLevel server) {
				cooldown = Math.round(ROW.attackCooldownS() * 20);
				swing(InteractionHand.MAIN_HAND);
				doHurtTarget(server, target);
			}
		}
	}

	/** Whether it has a target: the client plays the run animation and the chase sound. */
	public boolean isChasing() {
		return this.entityData.get(CHASING);
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return null;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return null;
	}

	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
	}
}
