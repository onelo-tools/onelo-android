package com.onelo.android

import com.onelo.android.internal.KeyValueStore
import java.util.Locale

/**
 * #1075 — the language this SDK's own texts are drawn in: the App's **Page
 * Language** (ADR-0043). The order is the language the App passes
 * ([OneloConfig.language]) when it is one of the App Languages, else the device
 * language when it is one, else the App's default — the same language as the
 * Hosted Pages.
 *
 * The SDK never chooses it. `/api/sdk/config` is asked with the App's language
 * (or the device language) plus the device locales as `Accept-Language`, and
 * answers `page_language`, chosen in Python (`app.lib.page_language`). The SDK
 * adopts that answer, keeps it for the next launch's first paint, and renders.
 * Until a first answer arrives (and with nothing kept from an earlier launch)
 * the texts are English: the language every App starts with. A backend that
 * omits the field (or answers `null`) leaves the language as it was.
 *
 * One value per process: an [OneloError]'s message has no SDK instance to ask.
 * An app that builds two `Onelo` instances gets the latest answer. Parity with
 * Swift `OneloPageLanguage` (#1074).
 */
internal object OneloPageLanguage {
    /** The languages Onelo translates its own texts into (`SUPPORTED_LANGUAGES`
     *  in `backend/app/lib/app_languages.py`). `pt` is European Portuguese. */
    val SUPPORTED: List<String> = listOf("en", "pl", "de", "fr", "es", "it", "pt", "nl")

    /** [KeyValueStore] key of the last adopted answer (first paint of the next launch). */
    const val STORAGE_KEY = "onelo_page_language"

    @Volatile private var value: String = "en"

    /** The Page Language the texts are drawn in now. */
    val current: String get() = value

    /**
     * Adopt the Page Language the backend answered. Tags compare by primary
     * subtag (`pl-PL` → `pl`). Returns the adopted value; `null` (nothing
     * changes) when the answer is missing or no language tag.
     */
    fun adopt(answer: String?): String? {
        val primary = primarySubtag(answer) ?: return null
        value = primary
        return primary
    }

    /** Adopt the `page_language` of an `/api/sdk/config` body and keep it in
     *  [store] for the next launch. Returns the adopted value or `null`. */
    fun adoptFromConfig(body: Map<String, Any?>, store: KeyValueStore?): String? {
        val adopted = adopt(body["page_language"] as? String) ?: return null
        try { store?.set(STORAGE_KEY, adopted) } catch (_: Exception) { /* cache is best-effort */ }
        return adopted
    }

    /** Seed from the answer kept by an earlier launch, so the first screen is
     *  already in the Page Language before `/api/sdk/config` answers. */
    fun restore(store: KeyValueStore?) {
        val kept = try { store?.get(STORAGE_KEY) } catch (_: Exception) { null }
        adopt(kept)
    }

    fun primarySubtag(tag: String?): String? {
        if (tag == null) return null
        val primary = tag.trim().split('-', '_').firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        if (primary.length !in 2..3 || !primary.all { it in 'a'..'z' }) return null
        return primary
    }

    /**
     * The device's preferred languages as an `Accept-Language` value
     * (`pl-PL, en;q=0.9, …`): the backend's next choice when the language asked
     * with (the App's, or the device's first) is not one the App offers.
     */
    fun acceptLanguage(tags: List<String>): String? {
        val usable = tags.filter { primarySubtag(it) != null }.distinct().take(6)
        if (usable.isEmpty()) return null
        return usable.mapIndexed { i, tag ->
            if (i == 0) tag else "$tag;q=${String.format(Locale.ROOT, "%.1f", maxOf(0.1, 1.0 - i * 0.1))}"
        }.joinToString(", ")
    }

    /** The device locales, most preferred first (`LocaleList`, API 24+); the
     *  default locale alone where the framework list is unavailable (JVM tests). */
    fun deviceLanguageTags(): List<String> {
        val tags = try {
            val list = android.os.LocaleList.getDefault()
            (0 until list.size()).map { list.get(it).toLanguageTag() }
        } catch (_: Throwable) {
            emptyList()
        }
        return tags.ifEmpty { listOf(OneloSignupMarketingConsent.deviceLanguage()) }
    }

    /** Back to the state of a fresh process. Tests only. */
    internal fun resetForTest() {
        value = "en"
    }
}

/**
 * #1075 — every text this SDK draws itself, in the eight languages Onelo
 * translates into. A developer's own content (app name, plan names, their
 * texts) is never here: it is shown as written. Log lines and messages that
 * address the developer (an invalid key, a missing configuration, raw server
 * diagnostics) stay English. Shared keys use the same words as Swift's
 * `OneloText` (#1074).
 */
internal enum class OneloText {
    // Chrome
    CLOSE,
    TRY_AGAIN,
    COULD_NOT_REFRESH_SIGN_IN,
    // Feature badges ({plan} is the developer's plan name, as written). The words
    // are the hosted pages' own: "Coming soon" / "Available in {plan}" are
    // hosted-notice-i18n.ts lockComingSoon / lockAvailableIn, the upsell fallback
    // is store-i18n.ts changePlan (pinned by BuiltInTexts1075Test).
    BADGE_NEW,
    BADGE_BETA,
    BADGE_COMING_SOON,
    BADGE_AVAILABLE_IN,
    BADGE_UPGRADE,
    // OneloError messages an App User may be shown ({detail}: technical detail)
    ERROR_NOT_AUTHENTICATED,
    ERROR_NETWORK,
    ERROR_CANCELLED,
    ERROR_SESSION_ENDED,
    ERROR_USER_REVOKED,
    ERROR_NO_ACTIVE_PLAN,
    ERROR_VERIFICATION_RATE_LIMITED,
    ERROR_INVALID_VERIFICATION_TOKEN;

    /** This text in the current Page Language. */
    fun text(): String = text(OneloPageLanguage.current)

    /** This text in [language]; English when Onelo has no translation for it. */
    fun text(language: String): String {
        val lang = OneloPageLanguage.primarySubtag(language) ?: "en"
        return TABLE[lang]?.get(this) ?: TABLE.getValue("en").getValue(this)
    }

    /** This text in the current Page Language with `{name}` filled in. */
    fun text(vararg fill: Pair<String, String>): String =
        fill.fold(text()) { acc, (name, value) -> acc.replace("{$name}", value) }

    companion object {
        val TABLE: Map<String, Map<OneloText, String>> = mapOf(
            "en" to mapOf(
                CLOSE to "Close",
                TRY_AGAIN to "Try again",
                COULD_NOT_REFRESH_SIGN_IN to "We couldn't refresh sign-in. Check your connection and try again.",
                BADGE_NEW to "New",
                BADGE_BETA to "Beta",
                BADGE_COMING_SOON to "Coming soon",
                BADGE_AVAILABLE_IN to "Available in {plan}",
                BADGE_UPGRADE to "Change plan",
                ERROR_NOT_AUTHENTICATED to "User is not authenticated",
                ERROR_NETWORK to "Network error: {detail}",
                ERROR_CANCELLED to "Sign in was cancelled",
                ERROR_SESSION_ENDED to "Your session ended — please sign in again",
                ERROR_USER_REVOKED to "This user account has been suspended",
                ERROR_NO_ACTIVE_PLAN to "No active subscription — the plan has expired or was cancelled.",
                ERROR_VERIFICATION_RATE_LIMITED to "At most 3 verification emails an hour — try again later",
                ERROR_INVALID_VERIFICATION_TOKEN to "This verification link is invalid, already used, replaced by a newer one, or expired",
            ),
            "pl" to mapOf(
                CLOSE to "Zamknij",
                TRY_AGAIN to "Spróbuj ponownie",
                COULD_NOT_REFRESH_SIGN_IN to "Nie udało się odświeżyć logowania. Sprawdź połączenie i spróbuj ponownie.",
                BADGE_NEW to "Nowość",
                BADGE_BETA to "Beta",
                BADGE_COMING_SOON to "Wkrótce",
                BADGE_AVAILABLE_IN to "Dostępne w planie {plan}",
                BADGE_UPGRADE to "Zmień plan",
                ERROR_NOT_AUTHENTICATED to "Użytkownik nie jest zalogowany",
                ERROR_NETWORK to "Błąd sieci: {detail}",
                ERROR_CANCELLED to "Logowanie zostało anulowane",
                ERROR_SESSION_ENDED to "Twoja sesja się zakończyła — zaloguj się ponownie",
                ERROR_USER_REVOKED to "To konto użytkownika zostało zawieszone",
                ERROR_NO_ACTIVE_PLAN to "Brak aktywnej subskrypcji — plan wygasł lub został anulowany.",
                ERROR_VERIFICATION_RATE_LIMITED to "Najwyżej 3 e-maile weryfikacyjne na godzinę — spróbuj później",
                ERROR_INVALID_VERIFICATION_TOKEN to "Ten link weryfikacyjny jest nieprawidłowy, został już użyty, zastąpiony nowszym albo wygasł",
            ),
            "de" to mapOf(
                CLOSE to "Schließen",
                TRY_AGAIN to "Erneut versuchen",
                COULD_NOT_REFRESH_SIGN_IN to "Die Anmeldung konnte nicht aktualisiert werden. Bitte prüfen Sie Ihre Verbindung und versuchen Sie es erneut.",
                BADGE_NEW to "Neu",
                BADGE_BETA to "Beta",
                BADGE_COMING_SOON to "Demnächst verfügbar",
                BADGE_AVAILABLE_IN to "Verfügbar im Plan {plan}",
                BADGE_UPGRADE to "Plan ändern",
                ERROR_NOT_AUTHENTICATED to "Der Benutzer ist nicht angemeldet",
                ERROR_NETWORK to "Netzwerkfehler: {detail}",
                ERROR_CANCELLED to "Die Anmeldung wurde abgebrochen",
                ERROR_SESSION_ENDED to "Ihre Sitzung ist abgelaufen — bitte melden Sie sich erneut an",
                ERROR_USER_REVOKED to "Dieses Benutzerkonto wurde gesperrt",
                ERROR_NO_ACTIVE_PLAN to "Kein aktives Abonnement — der Plan ist abgelaufen oder wurde gekündigt.",
                ERROR_VERIFICATION_RATE_LIMITED to "Höchstens 3 Bestätigungs-E-Mails pro Stunde — bitte versuchen Sie es später erneut",
                ERROR_INVALID_VERIFICATION_TOKEN to "Dieser Bestätigungslink ist ungültig, wurde bereits verwendet, durch einen neueren ersetzt oder ist abgelaufen",
            ),
            "fr" to mapOf(
                CLOSE to "Fermer",
                TRY_AGAIN to "Réessayer",
                COULD_NOT_REFRESH_SIGN_IN to "Impossible d’actualiser la connexion. Vérifiez votre accès à Internet et réessayez.",
                BADGE_NEW to "Nouveau",
                BADGE_BETA to "Bêta",
                BADGE_COMING_SOON to "Bientôt disponible",
                BADGE_AVAILABLE_IN to "Disponible avec le plan {plan}",
                BADGE_UPGRADE to "Changer de plan",
                ERROR_NOT_AUTHENTICATED to "L’utilisateur n’est pas connecté",
                ERROR_NETWORK to "Erreur réseau : {detail}",
                ERROR_CANCELLED to "La connexion a été annulée",
                ERROR_SESSION_ENDED to "Votre session a pris fin — veuillez vous reconnecter",
                ERROR_USER_REVOKED to "Ce compte utilisateur a été suspendu",
                ERROR_NO_ACTIVE_PLAN to "Aucun abonnement actif — le forfait a expiré ou a été résilié.",
                ERROR_VERIFICATION_RATE_LIMITED to "3 e-mails de vérification par heure au maximum — réessayez plus tard",
                ERROR_INVALID_VERIFICATION_TOKEN to "Ce lien de vérification n’est pas valide, a déjà été utilisé, a été remplacé par un plus récent ou a expiré",
            ),
            "es" to mapOf(
                CLOSE to "Cerrar",
                TRY_AGAIN to "Reintentar",
                COULD_NOT_REFRESH_SIGN_IN to "No hemos podido actualizar el inicio de sesión. Comprueba tu conexión e inténtalo de nuevo.",
                BADGE_NEW to "Nuevo",
                BADGE_BETA to "Beta",
                BADGE_COMING_SOON to "Próximamente",
                BADGE_AVAILABLE_IN to "Disponible en el plan {plan}",
                BADGE_UPGRADE to "Cambiar plan",
                ERROR_NOT_AUTHENTICATED to "El usuario no ha iniciado sesión",
                ERROR_NETWORK to "Error de red: {detail}",
                ERROR_CANCELLED to "Se ha cancelado el inicio de sesión",
                ERROR_SESSION_ENDED to "Tu sesión ha terminado — vuelve a iniciar sesión",
                ERROR_USER_REVOKED to "Esta cuenta de usuario ha sido suspendida",
                ERROR_NO_ACTIVE_PLAN to "No hay ninguna suscripción activa — el plan ha caducado o se ha cancelado.",
                ERROR_VERIFICATION_RATE_LIMITED to "Como máximo 3 correos de verificación por hora — inténtalo más tarde",
                ERROR_INVALID_VERIFICATION_TOKEN to "Este enlace de verificación no es válido, ya se ha usado, se ha sustituido por uno más reciente o ha caducado",
            ),
            "it" to mapOf(
                CLOSE to "Chiudi",
                TRY_AGAIN to "Riprova",
                COULD_NOT_REFRESH_SIGN_IN to "Non è stato possibile aggiornare l’accesso. Controlla la connessione e riprova.",
                BADGE_NEW to "Novità",
                BADGE_BETA to "Beta",
                BADGE_COMING_SOON to "Prossimamente",
                BADGE_AVAILABLE_IN to "Disponibile nel piano {plan}",
                BADGE_UPGRADE to "Cambia piano",
                ERROR_NOT_AUTHENTICATED to "L’utente non ha effettuato l’accesso",
                ERROR_NETWORK to "Errore di rete: {detail}",
                ERROR_CANCELLED to "L’accesso è stato annullato",
                ERROR_SESSION_ENDED to "La tua sessione è terminata — accedi di nuovo",
                ERROR_USER_REVOKED to "Questo account utente è stato sospeso",
                ERROR_NO_ACTIVE_PLAN to "Nessun abbonamento attivo — il piano è scaduto o è stato annullato.",
                ERROR_VERIFICATION_RATE_LIMITED to "Al massimo 3 e-mail di verifica all’ora — riprova più tardi",
                ERROR_INVALID_VERIFICATION_TOKEN to "Questo link di verifica non è valido, è già stato usato, è stato sostituito da uno più recente o è scaduto",
            ),
            "pt" to mapOf(
                CLOSE to "Fechar",
                TRY_AGAIN to "Tentar novamente",
                COULD_NOT_REFRESH_SIGN_IN to "Não foi possível atualizar o início de sessão. Verifique a sua ligação e tente novamente.",
                BADGE_NEW to "Novo",
                BADGE_BETA to "Beta",
                BADGE_COMING_SOON to "Brevemente",
                BADGE_AVAILABLE_IN to "Disponível no plano {plan}",
                BADGE_UPGRADE to "Mudar de plano",
                ERROR_NOT_AUTHENTICATED to "O utilizador não iniciou sessão",
                ERROR_NETWORK to "Erro de rede: {detail}",
                ERROR_CANCELLED to "O início de sessão foi cancelado",
                ERROR_SESSION_ENDED to "A sua sessão terminou — inicie sessão novamente",
                ERROR_USER_REVOKED to "Esta conta de utilizador foi suspensa",
                ERROR_NO_ACTIVE_PLAN to "Nenhuma subscrição ativa — o plano expirou ou foi cancelado.",
                ERROR_VERIFICATION_RATE_LIMITED to "No máximo 3 e-mails de verificação por hora — tente mais tarde",
                ERROR_INVALID_VERIFICATION_TOKEN to "Este link de verificação é inválido, já foi utilizado, foi substituído por um mais recente ou expirou",
            ),
            "nl" to mapOf(
                CLOSE to "Sluiten",
                TRY_AGAIN to "Opnieuw proberen",
                COULD_NOT_REFRESH_SIGN_IN to "We konden het inloggen niet vernieuwen. Controleer je verbinding en probeer het opnieuw.",
                BADGE_NEW to "Nieuw",
                BADGE_BETA to "Bèta",
                BADGE_COMING_SOON to "Binnenkort beschikbaar",
                BADGE_AVAILABLE_IN to "Beschikbaar in het plan {plan}",
                BADGE_UPGRADE to "Plan wijzigen",
                ERROR_NOT_AUTHENTICATED to "De gebruiker is niet ingelogd",
                ERROR_NETWORK to "Netwerkfout: {detail}",
                ERROR_CANCELLED to "Inloggen is geannuleerd",
                ERROR_SESSION_ENDED to "Je sessie is beëindigd — log opnieuw in",
                ERROR_USER_REVOKED to "Dit gebruikersaccount is opgeschort",
                ERROR_NO_ACTIVE_PLAN to "Geen actief abonnement — het abonnement is verlopen of opgezegd.",
                ERROR_VERIFICATION_RATE_LIMITED to "Maximaal 3 verificatie-e-mails per uur — probeer het later opnieuw",
                ERROR_INVALID_VERIFICATION_TOKEN to "Deze verificatielink is ongeldig, al gebruikt, vervangen door een nieuwere of verlopen",
            ),
        )
    }
}
