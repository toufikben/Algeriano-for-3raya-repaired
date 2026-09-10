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
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

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
            return SendResult(false, context.getString(R.string.ui_16b9f058a24f))
        }

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                // Opportunistic STARTTLS alone can be stripped by a network
                // downgrade. Require TLS to actually be negotiated and verify
                // the server certificate before any credentials or attachment
                // data are sent.
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.protocols", "TLSv1.2")
                put("mail.smtp.ssl.checkserveridentity", "true")
                // Android JavaMail may not select the platform trust manager
                // consistently for STARTTLS; scope the trust override to the
                // fixed Gmail host while retaining hostname verification.
                put("mail.smtp.ssl.trust", "smtp.gmail.com")
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "15000")
            }

            val cleanPassword = appPassword.replace(" ", "").trim()

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(senderEmail.trim(), cleanPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(senderEmail.trim(), context.getString(R.string.ui_6dc6e4eefce8)))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail.trim()))
                setSubject(subject, "UTF-8")
                eventId?.let {
                    setHeader("X-Security-Event-Id", it)
                    setHeader("Message-ID", "<$it@phone-fortress-security.local>")
                }

                val multipart: Multipart = MimeMultipart()

                val textPart = MimeBodyPart().apply {
                    setText(bodyText, "UTF-8", "plain")
                }
                multipart.addBodyPart(textPart)

                if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
                    val attachmentPart = MimeBodyPart().apply {
                        val source = FileDataSource(imageFile)
                        dataHandler = DataHandler(source)
                        fileName = "intruder_snapshot.jpg"
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
        } catch (e: Exception) {
            System.err.println("EmailSender: SMTP send failed: ${e.javaClass.simpleName}")
            SendResult(false, context.getString(R.string.ui_ece94e33ec57))
        }
    }
}
