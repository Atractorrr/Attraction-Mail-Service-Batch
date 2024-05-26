package attraction.run.gmail

import attraction.run.article.Article
import attraction.run.user.CannotAccessGmailException
import attraction.run.user.User
import attraction.run.user.UserMarkService
import com.google.api.client.auth.oauth2.BearerToken
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.ListLabelsResponse
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.ModifyMessageRequest
import org.apache.commons.codec.binary.Base64
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.util.MimeTypeUtils
import org.springframework.util.MimeTypeUtils.TEXT_HTML_VALUE
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@Component
class GmailReader(
        private val userMarkService: UserMarkService
) {
    private val log = LoggerFactory.getLogger(this.javaClass)!!

    private companion object {
        private const val APPLICATION_NAME = "attraction"
        private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()
        private const val CREDENTIALS_FILE_PATH = "/credentials.json"
    }

    fun getMemberInboxArticle(user: User): List<Article> {
        val httpTransport: NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport()
        val loadClientSecrets: GoogleClientSecrets = loadClientSecrets()

        val credentialResult = runCatching {
            getGoogleTokenResponse(httpTransport, user.refreshToken, loadClientSecrets.details)
        }.map {
            Credential(BearerToken.authorizationHeaderAccessMethod()).apply {
                accessToken = it.accessToken
            }
        }.onFailure {
            userMarkService.markTokenForReissue(user)
        }

        val credential = credentialResult.getOrElse { throw CannotAccessGmailException("${user.email} 사용자의 메일함에 접근할 수 없습니다.") }

        val gmailService = getGmailService(httpTransport, credential)
        return getMemberMessagesContent(gmailService, user)
    }

    private fun loadClientSecrets(): GoogleClientSecrets {
        val inputStream = GmailReader::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH)
                ?: throw FileNotFoundException("Resource not found: $CREDENTIALS_FILE_PATH")

        return inputStream.use {
            InputStreamReader(it).use { reader ->
                GoogleClientSecrets.load(JSON_FACTORY, reader)
            }
        }
    }

    private fun getGoogleTokenResponse(
            httpTransport: NetHttpTransport,
            refreshToken: String,
            details: GoogleClientSecrets.Details
    ): GoogleTokenResponse = GoogleRefreshTokenRequest(
            httpTransport,
            JSON_FACTORY,
            refreshToken,
            details.clientId,
            details.clientSecret
    ).execute()


    private fun getGmailService(httpTransport: NetHttpTransport, credential: Credential): Gmail {
        return Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build()
    }

    private fun getMemberMessagesContent(gmailService: Gmail, user: User): List<Article> {
        val messages = getMessages(gmailService, user)
        val messageIds = messages.map { it.id }
        log.info("사용자: ${user.email} message ids: $messageIds")

        return messages.map { message ->
            val messageDetails = gmailService.users().messages().get("me", message.id).setFormat("full").execute()

            createArticle(messageDetails).takeIf { it.isSameUserEmail(user.email) }
                    ?: throw IllegalArgumentException("사용자 이메일 정보가 올바르지 않습니다.")
        }.apply {
            removeUnReadLabel(messageIds, gmailService)
        }
    }

    private fun getMessages(gmailService: Gmail, user: User): MutableList<Message> {
        return gmailService.users().messages().list("me")
                .setQ("label:attraction is:unread")
                .execute()
                .messages ?: throw MailNotFoundException("${user.email} 사용자의 메일이 존재하지 않습니다.")
    }

    private fun createArticle(messageDetails: Message): Article {
        val associate = messageDetails.payload.headers
                .associate { it.name to it.value }

        val data = messageDetails.payload.body.data
        val contentHTML = String(Base64.decodeBase64(data), StandardCharsets.UTF_8)

        val newsletter = Newsletter(requireNotNull(associate["From"]) { "뉴스레터 정보가 존재하지 않습니다." })
        return Article(
                title = requireNotNull(associate["Subject"]) { "제목이 존재하지 않습니다." },
                newsletterEmail = newsletter.email,
                newsletterNickname = newsletter.nickname,
                userEmail = requireNotNull(associate["To"]?.extractEmailFromString()) { "사용자 이메일 형식이 올바르지 않습니다." },
                contentHTML = contentHTML,
                receivedAt = requireNotNull(associate["Date"]?.toLocalDateFromMailSendDate()) { "전송한 날짜가 올바르지 않습니다." }
        )
    }

    private fun String.extractEmailFromString(): String {
        val emailRegex = "<(.*?)>".toRegex()
        val matchResult = emailRegex.find(this)
        return matchResult?.groupValues?.get(1) ?: throw IllegalArgumentException("메일의 이메일이 형식이 올바르지 않습니다.")
    }

    private fun String.toLocalDateFromMailSendDate(): LocalDate {
        val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
        val zonedDateTime = ZonedDateTime.parse(this, formatter)
        return zonedDateTime.toLocalDate()
    }

    private fun removeUnReadLabel(messageIds: List<String>, gmailService: Gmail) {
        val mods = ModifyMessageRequest().setRemoveLabelIds(listOf("UNREAD"))
        messageIds.forEach {
            gmailService.users().messages().modify("me", it, mods).execute()
        }
    }
}

