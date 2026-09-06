package com.example.util

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
        senderEmail: String,
        appPassword: String,
        recipientEmail: String,
        subject: String,
        bodyText: String,
        imageFile: File? = null,
        eventId: String? = null
    ): SendResult {
        if (senderEmail.isBlank() || appPassword.isBlank()) {
            return SendResult(false, "البريد الإلكتروني أو كلمة مرور التطبيق فارغة")
        }

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.ssl.protocols", "TLSv1.2")
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
                setFrom(InternetAddress(senderEmail.trim(), "نظام حماية الهاتف"))
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
            SendResult(false, "فشل التحقق من بيانات SMTP؛ راجع البريد وكلمة مرور التطبيق", retryable = false)
        } catch (e: Exception) {
            System.err.println("EmailSender: SMTP send failed: ${e.javaClass.simpleName}")
            SendResult(false, "تعذر الاتصال بخادم البريد؛ ستتم إعادة المحاولة إذا كان الخطأ مؤقتًا")
        }
    }
}
