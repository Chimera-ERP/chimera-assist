package ro.chimeraerp.assist

/** One short-lived context, in memory only. A new call/session always supersedes old work. */
class CallContext(private val clock: () -> Long, private val ttl: Long = 5 * 60 * 1000L) {
    data class Event(val sequence: Long, val phone: String, val region: String?, val session: Session, val at: Long)
    private var sequence = 0L
    private var event: Event? = null
    private var result: Lookup? = null

    @Synchronized fun begin(phone: String, region: String?, session: Session): Event {
        result = null
        return Event(++sequence, phone, region, session, clock()).also { event = it }
    }
    @Synchronized fun current(session: Session?): Event? {
        val item = event ?: return null
        if (session != item.session || clock() - item.at !in 0 until ttl) { clear(); return null }
        return item
    }
    @Synchronized fun complete(item: Event, session: Session?, lookup: Lookup): Boolean {
        if (current(session) != item) return false
        result = lookup
        return true
    }
    @Synchronized fun result(session: Session?): Lookup? = if (current(session) != null) result else null
    @Synchronized fun clear() { sequence++; event = null; result = null }
}
