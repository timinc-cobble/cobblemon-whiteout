package us.timinc.mc.cobblemon.whiteout

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.battles.BattleEndedEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonFaintedEvent
import com.cobblemon.mod.common.api.scheduling.afterOnServer
import com.cobblemon.mod.common.util.getPlayer
import com.cobblemon.mod.common.util.isInBattle
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageType
import us.timinc.mc.cobblemon.timcore.AbstractConfig
import us.timinc.mc.cobblemon.timcore.AbstractMod

const val MOD_ID = "cobblemon_whiteout"

object Whiteout : AbstractMod<Whiteout.WhiteoutConfig>(MOD_ID, WhiteoutConfig::class.java) {
    @Suppress("MemberVisibilityCanBePrivate")
    val POKEBATTLE_DAMAGE_SOURCE: ResourceKey<DamageType> = ResourceKey.create(
        Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "pokebattle")
    )

    @Suppress("MemberVisibilityCanBePrivate")
    val POKEBATTLE_FLED_DAMAGE_SOURCE: ResourceKey<DamageType> = ResourceKey.create(
        Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "fled")
    )

    class WhiteoutConfig : AbstractConfig() {
        val healTeam: Boolean = false
        val showMessage: Boolean = true
        val killIfTeamKnockedOut: Boolean = true
        val killIfLost: Boolean = false
        val killIfFled: Boolean = false
    }

    init {
        CobblemonEvents.BATTLE_ENDED.subscribe(Priority.LOWEST, ::handleBattleEnd)
        CobblemonEvents.POKEMON_FAINTED.subscribe(Priority.LOWEST, ::handlePokemonFainted)
    }

    private fun handlePokemonFainted(evt: PokemonFaintedEvent) {
        if (config.killIfTeamKnockedOut && (evt.pokemon.getOwnerPlayer()?.isInBattle() == false)) {
            evt.pokemon.getOwnerPlayer()?.let { player ->
                if (Cobblemon.storage.getParty(player).all { it.currentHealth == 0 }) {
                    killEmAll(listOf(player), POKEBATTLE_DAMAGE_SOURCE)
                }
            }
        }
    }

    private fun handleBattleEnd(evt: BattleEndedEvent) {
        if (!(evt.wasFled || evt.wasForfeit || evt.wasVictory)) return
        if (evt.wasFled && config.killIfFled) handleBattleFled(evt.battle.players)
        if (!evt.wasFled) handleBattleVictory(evt)
    }

    private fun handleBattleFled(players: List<ServerPlayer>) = killEmAll(
        players,
        POKEBATTLE_FLED_DAMAGE_SOURCE
    )

    private fun handleBattleVictory(evt: BattleEndedEvent) {
        val playersToKill: MutableSet<ServerPlayer> = mutableSetOf()
        if (config.killIfTeamKnockedOut) {
            for (actor in evt.battle.winners + evt.battle.losers) {
                for (playerUUID in actor.getPlayerUUIDs()) {
                    val player = playerUUID.getPlayer() ?: continue
                    val playerParty = Cobblemon.storage.getParty(player)
                    if (playerParty.all { it.currentHealth == 0 }) {
                        playersToKill.add(player)
                    }
                }
            }
        }
        if (config.killIfLost) playersToKill.addAll(evt.battle.losers.flatMap { loser ->
            loser.getPlayerUUIDs().mapNotNull { it.getPlayer() }
        })
        killEmAll(playersToKill.toList(), POKEBATTLE_DAMAGE_SOURCE)
    }

    private fun killEmAll(players: List<ServerPlayer>, killer: ResourceKey<DamageType>) {
        players.forEach { player ->
            val playerParty = Cobblemon.storage.getParty(player)
            if (config.healTeam) {
                afterOnServer(1, player.level()) {
                    playerParty.heal()
                }
            }
            player.hurt(
                when (config.showMessage) {
                    true -> player.damageSources().source(killer)
                    false -> player.damageSources().genericKill()
                },
                Float.MAX_VALUE
            )
        }
    }
}
