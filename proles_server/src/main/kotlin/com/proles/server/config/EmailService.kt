package com.proles.server.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.simplejavamail.api.email.Email
import org.simplejavamail.api.mailer.Mailer
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder

/**
 * 📧 Email сервис через SMTP (SendGrid/Mailgun на порту 2525).
 *
 * Конфигурация через переменные окружения:
 * - EMAIL_ENABLED (true/false)
 * - SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD
 * - EMAIL_FROM (от кого)
 */
object EmailService {

    private var mailer: Mailer? = null
    private var enabled: Boolean = false
    private var fromAddress: String = ""
    private var host: String = ""
    private var port: Int = 2525

    fun init() {
        try {
            enabled = System.getenv("EMAIL_ENABLED")?.toBoolean() ?: false
            if (!enabled) {
                println("📧 Email service disabled (EMAIL_ENABLED != true)")
                return
            }

            fromAddress = System.getenv("EMAIL_FROM") ?: "Proles Timesheet <noreply@sendgrid.net>"
            host = System.getenv("SMTP_HOST") ?: "smtp.sendgrid.net"
            port = System.getenv("SMTP_PORT")?.toIntOrNull() ?: 2525
            val username = System.getenv("SMTP_USERNAME") ?: "apikey"
            val password = System.getenv("SMTP_PASSWORD") ?: ""

            if (password.isBlank()) {
                println("⚠️ Email service: SMTP_PASSWORD not set")
                enabled = false
                return
            }

            // 🔥 SendGrid/Mailgun используют STARTTLS на порту 2525
            mailer = MailerBuilder
                .withSMTPServer(host, port, username, password)
                .withTransportStrategy(TransportStrategy.SMTP_TLS)  // STARTTLS
                .withProperty("mail.smtp.timeout", "15000")
                .withProperty("mail.smtp.connectiontimeout", "15000")
                .withProperty("mail.smtp.ssl.trust", "*")
                .buildMailer()

            println("✅ Email service (SMTP) initialized: $host:$port (from: $fromAddress)")
        } catch (e: Exception) {
            println("❌ Email service init failed: ${e.message}")
            enabled = false
        }
    }

    /**
     * 📧 Отправляет HTML-письмо
     */
    fun sendHtmlEmail(
        to: String,
        subject: String,
        htmlBody: String,
        attachments: List<EmailAttachment> = emptyList()
    ) {
        if (!enabled || mailer == null) {
            println("⚠️ Email service disabled, skipping send to $to")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("📤 Sending email via SMTP to $to...")

                val emailBuilder = EmailBuilder.startingBlank()
                    .from(fromAddress)
                    .to(to)
                    .withSubject(subject)
                    .withHTMLText(htmlBody)

                attachments.forEach { att ->
                    val dataSource = object : jakarta.activation.DataSource {
                        override fun getInputStream() = att.bytes.inputStream()
                        override fun getOutputStream() = throw UnsupportedOperationException()
                        override fun getContentType() = att.mimeType
                        override fun getName() = att.fileName
                    }
                    emailBuilder.withAttachment(att.fileName, dataSource)
                }

                val email: Email = emailBuilder.buildEmail()
                mailer!!.sendMail(email, true)  // async=true

                println("✅ Email sent to $to: $subject (${attachments.size} attachments)")

            } catch (e: Exception) {
                println("❌ Email send FAILED to $to: ${e.javaClass.simpleName}: ${e.message}")

                // Детальная диагностика
                var cause: Throwable? = e.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    println("   ↳ Cause[$depth]: ${cause.javaClass.simpleName}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }

                e.printStackTrace()
            }
        }
    }

    /**
     * 📧 Отправляет plain-text письмо
     */
    fun sendPlainEmail(to: String, subject: String, text: String) {
        val htmlBody = "<pre style='font-family: monospace; white-space: pre-wrap;'>$text</pre>"
        sendHtmlEmail(to, subject, htmlBody)
    }
}

/**
 * 📎 Вложение для email
 */
data class EmailAttachment(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String = "application/octet-stream"
)