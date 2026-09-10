package com.example.util

import android.content.Context
import com.example.R

import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.PasswordAuthentication
import javax.mail.Provider
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.MessagingException

object EmailSender {

    data class SendResult(
        val isSuccess: Boolean,
        val errorMessage: String? = null,
        val retryable: Boolean = true
    )

    fun sendSecurityAlert(
        context: Context,
        senderEmail: String,
        appPassword: String,
        recipientEmail: String,
        subject: String,
        bodyText: String,
        imageFile: File? = null,
        eventId: String? = null
    ): SendResult {
        if (senderEmail.isBlank() || appPassword.isBlank()) {
            // FIX: blank credentials can never succeed — mark non-retryable
            // so callers go straight to FAILED_FINAL instead of 3 retries.
            return SendResult(false, context.getString(R.string.ui_16b9f058a24f), retryable = false)
        }

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
                put("mail.smtp.auth", "true")
                put("mail.smtp.auth.mechanisms", "LOGIN PLAIN")
                put("mail.smtp.starttls.enable", "true")
                // Opportunistic STARTTLS alone can be stripped by a network
                // downgrade. Require TLS to actually be negotiated and verify
                // the server certificate before any credentials or attachment
                // data are sent.
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.ssl.trust", "smtp.gmail.com")
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "15000")
            }

            val cleanPassword = appPassword.replace("\\s".toRegex(), "").trim()

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(senderEmail.trim(), cleanPassword)
                }
            })
            // Android release shrinking can remove JavaMail's provider registry.
            // Register SMTP explicitly so getTransport("smtp") is deterministic.
            if (session.getProvider("smtp") == null) {
                session.addProvider(
                    Provider(
                        Provider.Type.TRANSPORT,
                        "smtp",
                        "com.sun.mail.smtp.SMTPTransport",
                        "Eclipse Angus / JavaMail",
                        "1.6.7"
                    )
                )
            }

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(senderEmail.trim(), context.getString(R.string.ui_6dc6e4eefce8)))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail.trim()))
                setSubject(subject, "UTF-8")
                eventId?.let {
                    setHeader("X-Security-Event-Id", it)
                    // FIX: unique Message-ID per send attempt. Reusing
                    // <eventId@...> caused Gmail to dedupe retries.
                    setHeader("Message-ID", "<$it-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}@phone-fortress-security.local>")
                }

                val multipart: Multipart = MimeMultipart()

                val textPart = MimeBodyPart().apply {
                    setText(bodyText, "UTF-8", "plain")
                }
                multipart.addBodyPart(textPart)

                if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
                    val attachmentPart = MimeBodyPart().apply {
                        val source = FileDataSource(imageFile)
                        // FIX(email): explicit JPEG type + attachment disposition.
                        // Default octet-stream caused some Gmail clients to
                        // drop/preview-fail the intruder photo.
                        dataHandler = DataHandler(source)
                        fileName = "intruder_snapshot.jpg"
                        setHeader("Content-Type", "image/jpeg; name=\"intruder_snapshot.jpg\"")
                        setHeader("Content-Transfer-Encoding", "base64")
                        disposition = MimeBodyPart.ATTACHMENT
                    }
                    multipart.addBodyPart(attachmentPart)
                }

                setContent(multipart)
            }

            val transport = session.getTransport("smtp")
            try {
                transport.connect(senderEmail.trim(), cleanPassword)
                transport.sendMessage(message, message.allRecipients)
                SendResult(true)
            } finally {
                try {
                    if (transport.isConnected) transport.close()
                } catch (_: Exception) {
                    // The send result should not be replaced by a close failure.
                }
            }
        } catch (e: AuthenticationFailedException) {
            System.err.println("EmailSender: SMTP authentication failed")
            SendResult(false, context.getString(R.string.ui_464e2cc141b1), retryable = false)
        } catch (e: MessagingException) {
            val detail = smtpDiagnostic(e)
            val authenticationFailure = detail.contains("535") ||
                detail.contains("534") || detail.contains("5.7.8")
            System.err.println("EmailSender: SMTP messaging failure ${e.javaClass.simpleName}: $detail")
            SendResult(
                false,
                context.getString(R.string.ui_smtp_error_detail, e.javaClass.simpleName, detail),
                retryable = !authenticationFailure
            )
        } catch (e: Exception) {
            val detail = smtpDiagnostic(e)
            System.err.println("EmailSender: SMTP send failed: ${e.javaClass.simpleName}: $detail")
            SendResult(
                false,
                context.getString(R.string.ui_smtp_error_detail, e.javaClass.simpleName, detail),
                retryable = true
            )
        }
    }

    private fun smtpDiagnostic(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        repeat(4) {
            current?.let { throwable ->
                val text = throwable.message?.takeIf { it.isNotBlank() }
                parts += if (text == null) throwable.javaClass.simpleName
                else "${throwable.javaClass.simpleName}: $text"
                current = throwable.cause
            }
        }
        return parts.joinToString(" <- ").take(300)
    }
}
