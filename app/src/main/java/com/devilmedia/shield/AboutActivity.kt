package com.devilmedia.shield

import android.os.Bundle

/** A calm, honest explanation of what Devil Shield does. */
class AboutActivity : ShieldScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen("What Devil Shield protects you from")
        text("You may have seen worrying stories about hackers and devices in the news. Most of these attacks use a few simple routes in, and Devil Shield helps close them.")

        section(
            "Malware and hidden apps",
            "Some apps and cheap devices secretly connect to criminals' servers. Devil Shield blocks those servers, so hidden malware is cut off and can't be controlled."
        )
        section(
            "Scams and fake websites",
            "Known phishing and scam sites, the kind that try to steal passwords or card details, are blocked before they load."
        )
        section(
            "Your connection being used by others",
            "Infected devices are sometimes used to hide criminals' internet traffic behind your home connection. Blocking the servers that control them stops this."
        )
        section(
            "Spying",
            "The health check shows apps that can use the camera or microphone, or control the device, so you stay in charge."
        )
        section(
            "What stays the same",
            "Your apps keep working normally. Devil Shield only blocks known threats from public security lists, and it runs on this device. Nothing you do is sent to us."
        )
        section(
            "Being honest about limits",
            "No app can promise 100% protection. Devil Shield blocks known threats and helps you spot and close the gaps hackers use. Keeping devices updated and using strong passwords still matters."
        )
    }

    private fun section(title: String, detail: String) {
        val c = card()
        text(title, 17f, Brand.TEXT, bold = true, parent = c)
        text(detail, 15f, Brand.MUTED, parent = c)
    }
}
