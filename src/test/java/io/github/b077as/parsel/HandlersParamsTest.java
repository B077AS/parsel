package io.github.b077as.parsel;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.github.b077as.jolt.RpcException;
import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Security;
import io.github.b077as.parsel.settings.ThemeRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the page sends is read into the records of {@link Handlers} with Gson, the same way {@link ParselRpc} does it, before a handler
 * runs. These use the JSON shapes {@code api.ts} produces.
 */
class HandlersParamsTest {

    private final Gson gson = new Gson();

    @Test
    void aMissingRequiredTextFieldIsRefusedBeforeAHandlerRuns() {
        assertEquals("42", gson.fromJson("{\"id\":\"42\"}", Handlers.Id.class).id());
        // Jolt turns whatever the codec throws here into bad_params.
        assertThrows(RuntimeException.class, () -> gson.fromJson("{}", Handlers.Id.class));
        assertThrows(RuntimeException.class, () -> gson.fromJson("{\"id\":null}", Handlers.Id.class));
        assertThrows(RuntimeException.class, () -> gson.fromJson("{\"id\":\"1\"}", Handlers.MoveTo.class), "folder is missing");
    }

    @Test
    void booleansAndNumbersAreReadAndTheyAreNotCoercedFromWrongShapes() {
        var starred = gson.fromJson("{\"id\":\"7\",\"starred\":true}", Handlers.Starred.class);
        assertEquals("7", starred.id());
        assertTrue(starred.starred());
        assertThrows(JsonParseException.class, () -> gson.fromJson("{\"id\":\"7\",\"starred\":[1]}", Handlers.Starred.class));
        assertEquals(3, gson.fromJson("{\"id\":\"7\",\"index\":3}", Handlers.Attachment.class).index());
    }

    @Test
    void moveManyAndNewFolderKeepTheirOptionalFields() {
        var many = gson.fromJson("{\"ids\":[\"1\",\"2\"],\"action\":\"trash\"}", Handlers.MoveMany.class);
        assertEquals(List.of("1", "2"), many.ids());
        assertNull(many.folder(), "only the action move has a folder");

        var topLevel = gson.fromJson("{\"account\":\"1\",\"name\":\"Work\",\"parent\":null}", Handlers.NewFolder.class);
        assertNull(topLevel.parent());
        assertEquals("f3", gson.fromJson("{\"account\":\"1\",\"name\":\"Work\",\"parent\":\"f3\"}", Handlers.NewFolder.class).parent());
    }

    @Test
    void aSentMessageWithoutListsOrTextGetsEmptyOnes() {
        // The page sends no `body`: the plain text is written from the HTML.
        var sent = gson.fromJson("{\"account\":\"1\",\"to\":[\"a@example.com\"],\"subject\":\"Hi\",\"html\":\"<p>x</p>\","
                + "\"replyTo\":null,\"attachments\":[]}", Handlers.Outgoing.class);
        var draft = sent.toDraft();
        assertEquals(List.of("a@example.com"), draft.to());
        assertEquals(List.of(), draft.cc());
        assertEquals(List.of(), draft.bcc());
        assertEquals("", draft.body());
        assertNull(draft.replyTo());

        var bare = gson.fromJson("{\"account\":\"1\"}", Handlers.Outgoing.class);
        assertEquals("", bare.subject());
        assertEquals(List.of(), bare.attachments());
    }

    @Test
    void theAccountFormBecomesSettingsAndAnUnknownSecurityIsABadRequest() {
        String form = "{\"name\":\" Will \",\"email\":\" will@localhost \",\"username\":\"will\",\"imapHost\":\"127.0.0.1\",\"imapPort\":3143,"
                + "\"imapSecurity\":\"NONE\",\"smtpHost\":\"127.0.0.1\",\"smtpPort\":3025,\"smtpSecurity\":\"none\",\"password\":\"secret\"}";
        var parsed = gson.fromJson(form, Handlers.AccountForm.class);
        AccountSettings settings = parsed.settings();
        assertEquals("Will", settings.name());
        assertEquals("will@localhost", settings.email());
        assertEquals(Security.NONE, settings.smtpSecurity(), "the page's case does not matter");
        assertEquals("secret", parsed.password());

        var odd = gson.fromJson(form.replace("\"NONE\"", "\"TLS\""), Handlers.AccountForm.class);
        RpcException e = assertThrows(RpcException.class, odd::settings);
        assertEquals(RpcException.BAD_PARAMS, e.code());
    }

    @Test
    void theThemeIsCheckedBeforeItReachesTheWindow() {
        var ok = new ThemeRequest.Wire("system", "blue", "dark", "#101010", "#eeeeee", "#3b82f6", "#ffffff");
        ThemeRequest theme = ThemeRequest.parse(ok);
        assertEquals(0x101010, theme.background());

        var badColour = new ThemeRequest.Wire("system", "blue", "dark", "red", "#eeeeee", "#3b82f6", "#ffffff");
        assertEquals(RpcException.BAD_PARAMS, assertThrows(RpcException.class, () -> ThemeRequest.parse(badColour)).code());
        var missing = new ThemeRequest.Wire("system", null, "dark", "#101010", "#eeeeee", "#3b82f6", "#ffffff");
        assertEquals(RpcException.BAD_PARAMS, assertThrows(RpcException.class, () -> ThemeRequest.parse(missing)).code());
    }
}
