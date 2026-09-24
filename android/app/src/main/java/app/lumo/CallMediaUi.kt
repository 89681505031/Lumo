package app.lumo

internal fun formatCallElapsed(totalSeconds:Long):String {
    val safe=totalSeconds.coerceAtLeast(0L)
    val hours=safe/3600L
    val minutes=(safe%3600L)/60L
    val seconds=safe%60L
    return if(hours>0L)
        "%d:%02d:%02d".format(hours,minutes,seconds)
    else "%02d:%02d".format(minutes,seconds)
}
