package org.polarka.miner

import java.util.Locale

/** The app speaks English; on a phone set to Czech it speaks Czech. Both texts stand side by side in the code. */
fun tr(en: String, cs: String): String = if (Locale.getDefault().language == "cs") cs else en
