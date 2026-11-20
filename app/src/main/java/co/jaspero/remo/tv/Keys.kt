package co.jaspero.remo.tv

/** Android KeyEvent codes, which the TV protocol uses as-is. */
object Keys {
    const val HOME = 3
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val PLAY_PAUSE = 85
    const val NEXT = 87
    const val PREVIOUS = 88
    const val REWIND = 89
    const val FAST_FORWARD = 90
    const val MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val CAPTIONS = 175
    const val SETTINGS = 176
    const val TV_INPUT = 178

    /**
     * The key that types [c] on its own (no shift), or null. Used when the TV hasn't opened an
     * input session, since plain key presses still reach a focused text field.
     */
    fun forChar(c: Char): Int? = when (c) {
        in 'a'..'z' -> 29 + (c - 'a')
        in '0'..'9' -> 7 + (c - '0')
        ' ' -> SPACE
        '\n' -> ENTER
        '.' -> 56
        ',' -> 55
        '-' -> 69
        '=' -> 70
        '[' -> 71
        ']' -> 72
        '\\' -> 73
        ';' -> 74
        '\'' -> 75
        '/' -> 76
        '@' -> 77
        '+' -> 81
        '*' -> 17
        '#' -> 18
        '`' -> 68
        else -> null
    }
}
