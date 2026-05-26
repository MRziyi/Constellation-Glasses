package com.constellation.glass.hud

import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.constellation.glass.state.AppState
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Wraps [CXRLink]'s CustomView lifecycle. Owns the "what's currently on screen"
 * state and the body [ScrollWindow] for CARD.
 *
 * Lifecycle invariants (per GLASS-CLIENT-DESIGN.md §5):
 *   - HUD is CLOSED in IDLE
 *   - HUD is OPEN in LISTENING / THINKING / CARD / INSIGHT / OFFLINE
 *   - Each state-entry calls customViewOpen(...) with a fresh layout
 *   - Each state-internal update calls customViewUpdate(...) — node ids stable
 *   - Each state-exit (back to IDLE) calls customViewClose()
 *
 * The CXRLink instance is provided by ConstellationService (which also owns
 * connect/auth). HudRenderer never touches the connection itself.
 */
class HudRenderer(
    private val cxrLink: CXRLink,
    /** Called when the system service closes our CustomView out from under us
     *  (e.g. Sprite assistant takes over via "Hi Rokid"). We transition to
     *  IDLE; the next wake gesture re-opens. */
    private val onSystemClosed: () -> Unit,
) : HudSurface {
    private var currentState: AppState = AppState.Idle
    private var scrollWindow: ScrollWindow? = null
    private var currentCardId: String? = null

    init {
        cxrLink.setCXRCustomViewCbk(object : ICustomViewCbk {
            override fun onCustomViewOpened() {
                Timber.i("HudRenderer · onCustomViewOpened")
            }

            override fun onCustomViewUpdated() {
                // Per-update ack; noisy in THINKING. Trace only.
                Timber.v("HudRenderer · onCustomViewUpdated")
            }

            override fun onCustomViewClosed() {
                Timber.i("HudRenderer · onCustomViewClosed — system released us")
                // If we didn't expect this (Sprite preempted), notify caller.
                if (currentState != AppState.Idle) {
                    onSystemClosed()
                }
            }

            override fun onCustomViewIconsSent() {
                Timber.v("HudRenderer · onCustomViewIconsSent")
            }

            override fun onCustomViewError(code: Int, msg: String?) {
                Timber.w("HudRenderer · onCustomViewError code=$code msg=$msg")
            }
        })
    }

    /** Transition the HUD to a new state. Closes/opens as required. */
    override fun transition(prev: AppState, next: AppState) {
        if (prev == next) return
        Timber.i("HudRenderer · transition $prev → $next")
        currentState = next
        when (next) {
            AppState.Idle -> closeHud()
            AppState.Listening -> openHud(HudLayouts.listening().toJson())
            AppState.Thinking -> openHud(HudLayouts.thinking().toJson())
            AppState.Card -> {
                /* CARD content arrives via showCard() — open with an empty
                 * placeholder so we don't flash garbage. */
                openHud(HudLayouts.thinking(icon = "✦", detail = "preparing card…").toJson())
            }
            AppState.Insight -> {
                /* same: actual content via showInsight() */
                openHud(HudLayouts.thinking(icon = "✦", detail = "incoming insight…").toJson())
            }
            AppState.Offline -> openHud(HudLayouts.offline().toJson())
        }
    }

    // ── State-specific updaters (called from StateMachine on inbound frames) ──

    override fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?) {
        val (detailText, _) = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(detailRuns))
        val (metaText, _)   = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(metaRuns))
        val payload = HudLayouts.updateThinking(icon.ifEmpty { "⌛" }, detailText.ifEmpty { "…" }, metaText.takeIf { it.isNotEmpty() })
        safeUpdate(payload.toJson())
    }

    override fun updateListening(elapsedSec: Int) {
        val payload = HudLayouts.updateListeningElapsed(elapsedSec)
        safeUpdate(payload.toJson())
    }

    override fun showCard(
        cardId: String,
        titleRuns: JSONArray?,
        bodyRuns: JSONArray?,
        options: List<String>,
    ) {
        currentCardId = cardId
        val titleParsed = StyledRunsRenderer.parseRuns(titleRuns)
        val (titleText, titleStyle) = StyledRunsRenderer.flatten(titleParsed)
        val bodyParsed = StyledRunsRenderer.parseRuns(bodyRuns)
        val (bodyFlat, _) = StyledRunsRenderer.flatten(bodyParsed)

        // Wrap into the 6-line viewport
        val wrapped = ScrollWindow.wrap(bodyFlat, maxChars = 32)
        scrollWindow = ScrollWindow(wrapped, windowSize = 6)

        val layout = HudLayouts.card(
            titleText = if (titleText.isEmpty()) "Card" else titleText,
            titleStyle = StyledRunsRenderer.ttStyle(titleStyle),
            bodyText = scrollWindow!!.windowText(),
            scrollPos = scrollWindow!!.position(),
            scrollTotal = scrollWindow!!.totalWindows(),
            footer = footerFor(options),
        )
        openHud(layout.toJson())
    }

    override fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int) {
        val (titleText, _) = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(titleRuns))
        val (bodyText, _)  = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(bodyRuns))
        val layout = HudLayouts.insight(
            titleText = titleText.ifEmpty { "Insight" },
            bodyText = bodyText,
            ttlSec = ttlSec,
        )
        openHud(layout.toJson())
    }

    /** Ring SWIPE_UP or voice "上一段" → scroll body up one line. */
    override fun scrollCardUp(): Boolean {
        val w = scrollWindow ?: return false
        if (!w.scrollUp()) return false
        safeUpdate(HudLayouts.updateCardBody(w.windowText(), w.position(), w.totalWindows()).toJson())
        return true
    }

    /** Ring SWIPE_DOWN or voice "下一段" → scroll body down one line. */
    override fun scrollCardDown(): Boolean {
        val w = scrollWindow ?: return false
        if (!w.scrollDown()) return false
        safeUpdate(HudLayouts.updateCardBody(w.windowText(), w.position(), w.totalWindows()).toJson())
        return true
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun footerFor(options: List<String>): String {
        // Default: full 3-button vocabulary.
        if (options.isEmpty() || options.size == 1 && options[0] == "approve") {
            return "好 approve · 改 modify · 停 kill"
        }
        // Custom option set — render the actual labels with voice prefixes.
        val labels = options.map {
            when (it.lowercase()) {
                "approve" -> "好 approve"
                "modify"  -> "改 modify"
                "kill"    -> "停 kill"
                else      -> it
            }
        }
        return labels.joinToString(" · ")
    }

    private fun openHud(json: String) {
        val ok = try {
            cxrLink.customViewOpen(json)
        } catch (t: Throwable) {
            Timber.w(t, "HudRenderer · customViewOpen threw")
            false
        }
        if (!ok) Timber.w("HudRenderer · customViewOpen returned false")
    }

    private fun safeUpdate(json: String) {
        val ok = try {
            cxrLink.customViewUpdate(json)
        } catch (t: Throwable) {
            Timber.w(t, "HudRenderer · customViewUpdate threw")
            false
        }
        if (!ok) Timber.w("HudRenderer · customViewUpdate returned false")
    }

    private fun closeHud() {
        try { cxrLink.customViewClose() } catch (t: Throwable) {
            Timber.w(t, "HudRenderer · customViewClose threw")
        }
    }
}
