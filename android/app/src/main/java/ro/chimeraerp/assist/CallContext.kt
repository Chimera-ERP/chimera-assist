package ro.chimeraerp.assist

/** Volatile caller workspaces, scoped to one login. No phone history is persisted. */
class CallContext(private val clock: () -> Long, private val ttl: Long = 30 * 60 * 1000L) {
    data class Event(val sequence: Long, val phone: String, val region: String?, val session: Session, val at: Long)
    data class Cursor(val kind: String? = null, val partner: String? = null, val action: String? = null,
                      val offset: Int = 0, val item: String? = null, val detailOffset: Int = 0,
                      val scroll: Double = 0.0, val detailScroll: Double = 0.0)
    private data class Entry(val id: Long, var event: Event, var lastCall: Long,
                             var result: Lookup? = null, var cursor: Cursor = Cursor(), var expanded: Boolean = false)
    private val entries = linkedMapOf<String, Entry>()
    private val ongoing = mutableSetOf<String>()
    private var sequence = 0L
    private var selected: String? = null
    private var identity: Session? = null
    private var inCall = false

    private fun scope(session: Session?): Boolean {
        if (session == null || identity?.sameLogin(session) != true) { clear(); return false }
        prune()
        return true
    }
    private fun prune() {
        val now = clock()
        entries.entries.removeAll { (key, entry) -> key !in ongoing && now - entry.lastCall !in 0 until ttl }
        if (!entries.containsKey(selected)) selected = null
    }
    @Synchronized fun begin(phone: String, region: String?, session: Session): Event {
        if (identity?.sameLogin(session) != true) { clear(); identity = session }
        prune()
        val canonical = PhoneNumbers.canonical(phone, region)
        val event = Event(++sequence, canonical, region, session, clock())
        val entry = entries[canonical]
        if (entry == null) entries[canonical] = Entry(sequence, event, clock(), expanded = entries[selected]?.expanded ?: false)
        else { entry.event = event; entry.lastCall = clock() }
        selected = canonical
        if (inCall) ongoing.add(canonical)
        return event
    }
    /** Aggregate Telecom state includes ringing, active and held calls across SIMs. */
    @Synchronized fun callState(active: Boolean) {
        if (active) {
            if (!inCall) selected?.takeIf { clock() - (entries[it]?.lastCall ?: Long.MIN_VALUE) in 0..20_000 }?.let { ongoing.add(it) }
        } else if (inCall) {
            ongoing.forEach { entries[it]?.lastCall = clock() }
            ongoing.clear()
        }
        inCall = active
        prune()
    }
    @Synchronized fun current(session: Session?): Event? = if (scope(session)) entries[selected]?.event else null
    @Synchronized fun complete(item: Event, session: Session?, lookup: Lookup): Boolean {
        if (current(session) != item) return false
        val entry = entries[selected] ?: return false
        entry.result = lookup
        val cursor = entry.cursor
        val partner = lookup.partners.find { it.kind == cursor.kind && it.id == cursor.partner }
        if (partner == null || cursor.action != null && cursor.action !in partner.actions) entry.cursor = Cursor()
        return true
    }
    @Synchronized fun result(session: Session?): Lookup? = if (scope(session)) entries[selected]?.result else null
    @Synchronized fun id(session: Session?): Long? = if (scope(session)) entries[selected]?.id else null
    @Synchronized fun cursor(session: Session?): Cursor = if (scope(session)) entries[selected]?.cursor ?: Cursor() else Cursor()
    @Synchronized fun navigate(session: Session?, id: Long, cursor: Cursor): Boolean {
        if (!scope(session)) return false
        val entry = entries[selected]?.takeIf { it.id == id } ?: return false
        val partner = entry.result?.partners?.find { it.kind == cursor.kind && it.id == cursor.partner } ?: return false
        require(cursor.action == null || cursor.action in partner.actions)
        require(cursor.offset in 0..10000 && cursor.detailOffset in 0..10000)
        require(cursor.item == null || cursor.item.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(cursor.scroll.isFinite() && cursor.scroll in 0.0..1000000.0 && cursor.detailScroll.isFinite() && cursor.detailScroll in 0.0..1000000.0)
        entry.cursor = cursor
        return true
    }
    @Synchronized fun expanded(session: Session?): Boolean = if (scope(session)) entries[selected]?.expanded == true else false
    @Synchronized fun expanded(session: Session?, value: Boolean) { if (scope(session)) entries[selected]?.expanded = value }
    @Synchronized fun clear() {
        sequence++; entries.clear(); ongoing.clear(); selected = null; identity = null; inCall = false
    }
}
