package com.example.util

import android.content.Context
import android.util.Log
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

    private const val TAG = "EmailSender"

    enum class ErrorCode {
        SMTP_AUTH_FAILED,      // Non-retryable
        SMTP_CONNECTION_TIMEOUT,  // Retryable
        SMTP_SEND_TIMEOUT,     // Retryable
        SMTP_NETWORK_ERROR,    // Retryable
        SMTP_CERTIFICATE_ERROR, // Non-retryable
        SMTP_UNKNOWN_ERROR     // Retryable
    }

    data class SendResult(
        val isSuccess: Boolean,
        val errorMessage: String? = null,
        val retryable: Boolean = true,
        val errorCode: ErrorCode? = null
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
            Log.w(TAG, "Email or password is blank")
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
                Log.d(TAG, "Email sent successfully for event: $eventId")
                SendResult(true)
            } finally {
                try {
                    if (transport.isConnected) transport.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing SMTP transport", e)
                    // The send result should not be replaced by a close failure.
                }
            }
        } catch (e: AuthenticationFailedException) {
            Log.e(TAG, "SMTP authentication failed", e)
            // Non-retryable: credentials are wrong
            SendResult(
                false,
                context.getString(R.string.ui_464e2cc141b1),
                retryable = false,
                errorCode = ErrorCode.SMTP_AUTH_FAILED
            )
        } catch (e: javax.mail.MessagingException) {
            val (errorCode, retryable, message) = classifySmtpError(e, context)
            Log.e(TAG, "SMTP error: $errorCode (retryable=$retryable)", e)
            SendResult(
                false,
                message,
                retryable = retryable,
                errorCode = errorCode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending email: ${e.javaClass.simpleName}", e)
            SendResult(
                false,
                context.getString(R.string.ui_ece94e33ec57),
                retryable = true,
                errorCode = ErrorCode.SMTP_UNKNOWN_ERROR
            )
        }
    }

    /**
     * Classify SMTP errors into retryable and non-retryable categories.
     * Does NOT expose raw server messages to UI.
     */
    private fun classifySmtpError(
        e: javax.mail.MessagingException,
        context: Context
    ): Triple<ErrorCode, Boolean, String> {
        val cause = e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
        val message = e.message ?: ""

        return when {
            cause.contains("SSLException") || cause.contains("CertPathValidatorException") -> {
                Triple(
                    ErrorCode.SMTP_CERTIFICATE_ERROR,
                    false, // Non-retryable
                    context.getString(R.string.ui_ece94e33ec57)
                )
            }
            message.contains("Connection timed out", ignoreCase = true) -> {
                Triple(
                    ErrorCode.SMTP_CONNECTION_TIMEOUT,
                    true, // Retryable
                    context.getString(R.string.ui_ece94e33ec57)
                )
            }
            message.contains("timeout", ignoreCase = true) -> {
                Triple(
                    ErrorCode.SMTP_SEND_TIMEOUT,
                    true, // Retryable
                    context.getString(R.string.ui_ece94e33ec57)
                )
            }
            message.contains("Network is unreachable", ignoreCase = true) ||
            message.contains("No address associated", ignoreCase = true) -> {
                Triple(
                    ErrorCode.SMTP_NETWORK_ERROR,
                    true, // Retryable
                    context.getString(R.string.ui_ece94e33ec57)
                )
            }
            else -> {
                Triple(
                    ErrorCode.SMTP_UNKNOWN_ERROR,
                    true, // Retryable by default
                    context.getString(R.string.ui_ece94e33ec57)
                )
            }
        }
    }
}
