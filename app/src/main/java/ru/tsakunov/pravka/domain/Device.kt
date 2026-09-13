package ru.tsakunov.pravka.domain

/**
 * Электронная книга на Android определяется по производителю: на ней режим ридера и крупный интерфейс
 * включаются по умолчанию. Список короткий и намеренно осторожный: ошибка стоит лишь одного тумблера в
 * настройках, а не сломанного интерфейса на обычном телефоне.
 */
private val EINK_MARKERS = listOf("onyx", "boox", "pocketbook", "bigme", "meebook", "boyue", "likebook", "dasung", "remarkable")

fun isEinkDevice(manufacturer: String?, brand: String?, model: String?): Boolean {
    val haystack = listOfNotNull(manufacturer, brand, model).joinToString(" ").lowercase()
    return EINK_MARKERS.any { it in haystack }
}
