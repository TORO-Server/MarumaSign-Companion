package net.torosaba.marumasign.companion

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side

/**
 * 看板の URL を書き換える (メインスレッドで呼ぶ)。チャンクを読み込ませることはしない (読み込まれていなければ NOT_LOADED)。
 * 書き換えるのは、看板がまだ from の URL のときだけ。URL より後ろ (位置・大きさ・回転など) はそのまま残す。
 */
internal object SignRewriter {

    enum class Outcome {
        DONE,
        /** チャンクやワールドが読み込まれていない。読み込まれたらもう一度 */
        NOT_LOADED,
        /** だれかが編集している。あとでもう一度 */
        BUSY,
        /** 看板がなくなった、別の URL に変わった。もう書き換えない */
        CHANGED,
        /** 書き換えると看板に入りきらない */
        TOO_LONG,
    }

    /** 書き換えた結果。DONE のときは新しい文字列と向き (地図などへの反映に使う) */
    class Applied(val outcome: Outcome, val text: String = "", val orient: Int = Orient.NONE)

    fun apply(r: HostedStore.Rewrite): Applied {
        val world = Bukkit.getWorld(r.world) ?: return Applied(Outcome.NOT_LOADED)
        if (!world.isChunkLoaded(r.x shr 4, r.z shr 4)) return Applied(Outcome.NOT_LOADED)
        val block = world.getBlockAt(r.x, r.y, r.z)
        val sign = block.getState(false) as? Sign ?: return Applied(Outcome.CHANGED)
        if (sign.allowedEditorUniqueId != null) return Applied(Outcome.BUSY)
        val text = SignListener.join(sign.getSide(Side.FRONT).lines()) + SignListener.join(sign.getSide(Side.BACK).lines())
        val spec = MarumaSpec.parse(text) ?: return Applied(Outcome.CHANGED)
        if (spec.url != r.from) return Applied(Outcome.CHANGED)
        val replaced = SignLines.replaceUrl(text, r.to) ?: return Applied(Outcome.CHANGED)
        val sides = SignLines.split(replaced) ?: return Applied(Outcome.TOO_LONG)
        for (i in 0 until SignLines.LINES_PER_SIDE) {
            sign.getSide(Side.FRONT).line(i, Component.text(sides.front[i]))
            sign.getSide(Side.BACK).line(i, Component.text(sides.back[i]))
        }
        sign.update(true, false)
        return Applied(Outcome.DONE, replaced, SignListener.orientOf(block))
    }
}
