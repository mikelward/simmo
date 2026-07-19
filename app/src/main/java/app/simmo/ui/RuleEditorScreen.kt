package app.simmo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simmo.R
import app.simmo.domain.ContactCallApp
import app.simmo.domain.CustomGroup
import app.simmo.domain.DialHandoffApp
import app.simmo.domain.PhoneAccountRef
import app.simmo.domain.Rule
import app.simmo.domain.RuleAction
import app.simmo.domain.RuleMatcher
import app.simmo.domain.SimRef
import app.simmo.domain.destinationMatcher
import app.simmo.domain.groupIds
import app.simmo.domain.regionCodes
import app.simmo.notify.SimNotifications
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the editor is producing — a new rule or an edit to an existing one.
 * Serializable so the open editor route can be persisted in saved state and
 * survive process death, not just configuration change.
 */
@Serializable
sealed interface EditorTarget {
    /**
     * The new-SIM prompt's path: [presetSim] preselects that SIM's action,
     * and [presetRegion] (the SIM's home country, when known) preseeds the
     * matcher so the editor opens on the countries branch — accepting the
     * prompt must never quietly produce an any-destination rule.
     */
    @Serializable
    @SerialName("new")
    data class New(
        val presetSim: SimRef? = null,
        val presetRegion: String? = null,
    ) : EditorTarget

    /**
     * Editing an existing rule, addressed by its stable [Rule.id] rather than
     * its list position: the editor route can outlive process death and
     * concurrent list changes (a delete-and-undo elsewhere), and an id lands
     * the save on the intended rule where a stale index would not. [rule] is
     * the snapshot the editor opened on, kept for prefill.
     */
    @Serializable
    @SerialName("existing")
    data class Existing(val id: String, val rule: Rule) : EditorTarget
}

/** The editor's current choices; drives which action needs a SIM selection. */
data class EditorDraft(
    val matcher: RuleMatcher,
    val action: RuleAction,
)

@Composable
fun RuleEditorScreen(
    viewModel: RulesViewModel,
    target: EditorTarget,
    onDone: () -> Unit,
) {
    val simOptions by viewModel.simOptions.collectAsStateWithLifecycle()
    val countryOptions by viewModel.countryOptions.collectAsStateWithLifecycle()
    val suggestedCountries by viewModel.suggestedCountries.collectAsStateWithLifecycle()
    val groupOptions by viewModel.groupOptions.collectAsStateWithLifecycle()
    val handOffApps by viewModel.handOffApps.collectAsStateWithLifecycle()
    val dialHandoffApps by viewModel.dialHandoffApps.collectAsStateWithLifecycle()
    val callingAccounts by viewModel.callingAccounts.collectAsStateWithLifecycle()
    // Contacts (READ_CONTACTS) back two things here: the app-to-app hand-off's
    // reverse lookup, and the country picker's "Suggested" bucket. Both read the
    // same warm index, so one grant serves both. Request it when the user picks a
    // contact-app action or taps the picker's suggest affordance; a grant
    // refreshes the index and flips [contactsGranted] so the affordance yields to
    // the filled bucket.
    val context = LocalContext.current
    var contactsGranted by remember { mutableStateOf(isContactsGranted(context)) }
    // Notifications back the "couldn't open <app>" failure notice, so picking a
    // hand-off action asks for POST_NOTIFICATIONS (once — a denial isn't
    // re-nagged on every pick) and, while notifications stay off, the selected
    // hand-off row shows a tappable enable hint. `areNotificationsEnabled`
    // rather than the bare permission check: notifications turned off in system
    // settings (any SDK) mute the notice just the same.
    var notificationsEnabled by remember { mutableStateOf(areNotificationsEnabled(context)) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsEnabled = areNotificationsEnabled(context)
    }
    fun requestNotifications() {
        if (canRequestNotifications(context) && !notificationsAsked) {
            notificationsAsked = true
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Picking WhatsApp needs contacts *and* notifications; two launchers can't
    // be in flight together, so the contacts result chains into the
    // notifications ask (the callback also fires immediately when contacts are
    // already granted). The picker's suggest affordance shares the launcher but
    // not the chain — suggesting countries has nothing to notify about.
    var chainNotificationsRequest by remember { mutableStateOf(false) }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        contactsGranted = granted
        if (granted) viewModel.onContactsAccessGranted()
        if (chainNotificationsRequest) {
            chainNotificationsRequest = false
            requestNotifications()
        }
    }
    // A grant or revoke made in Settings bypasses the launcher, so re-check on
    // every resume — otherwise a revocation would leave the prompt suppressed
    // (stale true) with no way to re-request from the picker. MainActivity does
    // the same for its top-level permission state and the warm index refresh.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                contactsGranted = isContactsGranted(context)
                notificationsEnabled = areNotificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    RuleEditorContent(
        target = target,
        simOptions = simOptions,
        countryOptions = countryOptions,
        suggestedCountries = suggestedCountries,
        contactsGranted = contactsGranted,
        groupOptions = groupOptions,
        handOffApps = handOffApps,
        dialHandoffApps = dialHandoffApps,
        callingAccounts = callingAccounts,
        onRequestContactsAccess = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
        notificationsEnabled = notificationsEnabled,
        onRequestNotifications = ::requestNotifications,
        onContactHandOffPicked = {
            chainNotificationsRequest = true
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        },
        onEnableNotifications = {
            // The hint must always lead somewhere: request while the dialog can
            // still show, otherwise (already asked and denied, or notifications
            // off in settings) open the app's notification settings.
            if (canRequestNotifications(context) && !notificationsAsked) {
                notificationsAsked = true
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        },
        onSave = { pendingGroups, draft ->
            val rule = ruleFromDraft(draft, target)
            when (target) {
                is EditorTarget.New -> {
                    val presetSim = target.presetSim
                    if (presetSim != null) viewModel.addRuleForNewSim(presetSim, rule, pendingGroups)
                    else viewModel.addRule(rule, pendingGroups)
                }
                is EditorTarget.Existing -> viewModel.replaceRule(target.id, rule, pendingGroups)
            }
            onDone()
        },
        onDelete = (target as? EditorTarget.Existing)?.let { existing ->
            { viewModel.removeRule(existing.id); onDone() }
        },
        onCancel = onDone,
    )
}

@Composable
internal fun RuleEditorContent(
    target: EditorTarget,
    simOptions: List<SimOptionUi>,
    countryOptions: List<CountryOptionUi>,
    /** Saves the rule and, in the same transaction, any groups built in the picker. */
    onSave: (pendingGroups: List<CustomGroup>, draft: EditorDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    /** Contact-derived countries shown atop the picker; empty hides the section. */
    suggestedCountries: List<CountryOptionUi> = emptyList(),
    /** Whether READ_CONTACTS is granted; false shows the picker's "suggest" affordance. */
    contactsGranted: Boolean = true,
    groupOptions: List<CountryGroupOptionUi> = emptyList(),
    /** Installed app-to-app hand-off targets; each is offered as an action. */
    handOffApps: Set<ContactCallApp> = emptySet(),
    /** Reachable dial-intent hand-off targets (Google Voice, Teams); each is an action. */
    dialHandoffApps: Set<DialHandoffApp> = emptySet(),
    /** Enabled non-SIM calling accounts (SIP providers); each is an action. */
    callingAccounts: List<CallingAccountOptionUi> = emptyList(),
    /** Invoked from the country picker's suggest affordance, to request READ_CONTACTS. */
    onRequestContactsAccess: () -> Unit = {},
    /** False while notifications are off; a selected hand-off action then shows the enable hint. */
    notificationsEnabled: Boolean = true,
    /** Invoked when a dial-intent hand-off action is picked, to request POST_NOTIFICATIONS. */
    onRequestNotifications: () -> Unit = {},
    /** Invoked when a contact-app action is picked, to request READ_CONTACTS then POST_NOTIFICATIONS. */
    onContactHandOffPicked: () -> Unit = {},
    /** The hint's tap: request the permission or open the app's notification settings. */
    onEnableNotifications: () -> Unit = {},
) {
    // rememberSaveable so an in-progress edit survives rotation / recreation.
    val initial = (target as? EditorTarget.Existing)?.rule
    val preset = target as? EditorTarget.New
    // A preset (new-SIM prompt) editor starts on the countries branch —
    // prefilled with the SIM's home country when the platform reported one,
    // otherwise empty so Save stays disabled until the user scopes the rule.
    // Only an explicit "Any country" tap widens it to every destination.
    val initialRegions = initial?.matcher?.regionCodes().orEmpty()
        .ifEmpty { preset?.presetRegion?.let { listOf(it) }.orEmpty() }
    val initialGroups = initial?.matcher?.groupIds().orEmpty()
    var matchesAny by rememberSaveable {
        mutableStateOf(
            if (preset?.presetSim != null) false
            else initialRegions.isEmpty() && initialGroups.isEmpty(),
        )
    }
    // The rule's countries and groups, in the order added; kept when toggling
    // to "any" so switching back doesn't lose them (dropped only on save).
    var regions by rememberSaveable(stateSaver = RegionsSaver) {
        mutableStateOf(initialRegions)
    }
    var groups by rememberSaveable(stateSaver = RegionsSaver) {
        mutableStateOf(initialGroups)
    }
    // Groups built from the picker this session but not yet committed: persisted
    // only when the rule is saved (group first, then the rule). So a cancelled
    // rule leaves no orphan group, and a saved rule never references a group that
    // isn't persisted (Codex on PR #35). Saveable so a pending group survives
    // rotation / process death alongside the rule draft.
    var pendingGroups by rememberSaveable(stateSaver = PendingGroupsSaver) {
        mutableStateOf(emptyList<CustomGroup>())
    }
    // Delete asks first; saveable so the confirm survives a rotation.
    // An existing rule whose action the editor can't represent (a hand-off
    // target this version doesn't know): kept verbatim so saving an edit to
    // its country never silently rewrites the action. Null for new rules and
    // editor-representable ones.
    val keepAction = initial?.action?.takeIf { it is RuleAction.HandOff && ActionChoice.of(it) == null }
    // Null means "keep the unsupported original action" (only reachable when
    // keepAction is non-null); a concrete choice replaces it. A new rule
    // opened from the new-SIM prompt starts on that SIM's action.
    val presetSim = preset?.presetSim
    var actionChoice by rememberSaveable {
        mutableStateOf(if (presetSim != null) ActionChoice.USE_SIM else ActionChoice.of(initial?.action))
    }
    // Holds the raw stored/tapped ref; the *displayed* selection is resolved
    // from it against the current options below, so a SIM whose id was
    // invalidated or reissued after a restore still highlights the right row.
    var simRef by rememberSaveable(stateSaver = SimRefSaver) {
        mutableStateOf(presetSim ?: (initial?.action as? RuleAction.UseSim)?.sim)
    }
    val selectedSimRef = resolveSelectedSim(simRef, simOptions)
    // The rule's calling account (SIP provider, VoIP app), same shape as the
    // SIM selection: the stored/tapped choice is resolved against the live
    // accounts, and a stored account that is no longer registered still shows
    // (marked unavailable) so re-saving the rule doesn't drop it.
    var accountChoice by rememberSaveable(stateSaver = AccountSaver) {
        mutableStateOf(
            (initial?.action as? RuleAction.HandOff.ViaPhoneAccount)?.let {
                CallingAccountOptionUi(it.account, it.displayLabel())
            },
        )
    }
    val accountRows = accountOptions(callingAccounts, accountChoice)
    val selectedAccount = resolveSelectedAccount(accountChoice, accountRows)

    // The country picker is a full-screen sub-step of the editor rather than a
    // separate navigation route, so the editor's draft above stays composed
    // (and thus retained) while it's open. Both flags are rememberSaveable so a
    // rotation or process death mid-search reopens the picker where it was.
    var showCountryPicker by rememberSaveable { mutableStateOf(false) }
    var showGroupEditor by rememberSaveable { mutableStateOf(false) }
    var countryQuery by rememberSaveable { mutableStateOf("") }
    // Making a group mid-rule: a full-screen sub-step (like the picker) so the
    // rule draft stays composed. On save the new group is created and added to
    // this rule; either way we return to the editor, not back into the picker.
    if (showGroupEditor) {
        GroupEditor(
            initial = null,
            countryOptions = countryOptions,
            onSave = { name, memberRegions ->
                val group = CustomGroup(CustomGroup.newId(), name.trim(), memberRegions.map { it.uppercase() })
                pendingGroups = pendingGroups + group
                if (group.id !in groups) groups = groups + group.id
                matchesAny = false
                showGroupEditor = false
            },
            onDelete = null,
            onCancel = { showGroupEditor = false },
        )
        return
    }
    if (showCountryPicker) {
        CountryPickerContent(
            options = countryOptions,
            suggested = suggestedCountries,
            contactsGranted = contactsGranted,
            onRequestContacts = onRequestContactsAccess,
            query = countryQuery,
            onQueryChange = { countryQuery = it },
            selectedRegions = regions.map { it.uppercase() }.toSet(),
            onSelect = { picked ->
                if (regions.none { it.equals(picked, ignoreCase = true) }) {
                    regions = regions + picked
                }
                matchesAny = false
                countryQuery = ""
                showCountryPicker = false
            },
            groups = groupOptions,
            selectedGroupIds = groups.toSet(),
            onSelectGroup = { picked ->
                if (picked !in groups) groups = groups + picked
                matchesAny = false
                countryQuery = ""
                showCountryPicker = false
            },
            onCreateGroup = {
                countryQuery = ""
                showCountryPicker = false
                showGroupEditor = true
            },
            onBack = {
                countryQuery = ""
                showCountryPicker = false
            },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            // Everything above the button bar scrolls in one list, so no
            // section can starve another or push the buttons off-screen.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(
                            if (target is EditorTarget.Existing) R.string.editor_title_edit
                            else R.string.editor_title_new,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }

                item {
                    Text(stringResource(R.string.editor_when_label), style = MaterialTheme.typography.titleMedium)
                }
                item {
                    ChoiceRow(
                        selected = matchesAny,
                        text = stringResource(R.string.rule_matcher_any),
                        onSelect = { matchesAny = true },
                    )
                }
                // Each chosen group and country is a removable entry; the add
                // row below opens the searchable picker rather than listing
                // ~240 countries inline. Entries stay visible (dimmed) while
                // "Any country" is selected, same as the old single-country
                // label did, so switching back is one tap with nothing lost.
                items(groups, key = { "group:$it" }) { entry ->
                    // A pending group isn't in groupOptions yet (persisted only on
                    // Save), so resolve its name from the pending list too.
                    val label = groupOptions.firstOrNull { it.id == entry }?.label
                        ?: pendingGroups.firstOrNull { it.id == entry }?.name
                        ?: entry
                    SelectedCountryRow(
                        label = label,
                        dimmed = matchesAny,
                        onSelect = { matchesAny = false },
                        onRemove = { groups = groups.filterNot { it == entry } },
                    )
                }
                items(regions, key = { "region:$it" }) { entry ->
                    val label = countryOptions
                        .firstOrNull { it.regionCode.equals(entry, ignoreCase = true) }?.label
                        ?: entry
                    SelectedCountryRow(
                        label = label,
                        dimmed = matchesAny,
                        onSelect = { matchesAny = false },
                        onRemove = { regions = regions.filterNot { it == entry } },
                    )
                }
                item {
                    AddCountryRow(onClick = { showCountryPicker = true })
                }

                item {
                    Text(stringResource(R.string.editor_do_label), style = MaterialTheme.typography.titleMedium)
                }
                if (keepAction != null) {
                    // The rule's current action can't be edited here (a
                    // hand-off target this version doesn't know). Show it as a
                    // preselected row so the user can see and keep it; the
                    // action is only rewritten if they pick a control below.
                    item {
                        ChoiceRow(
                            selected = actionChoice == null,
                            text = stringResource(
                                R.string.rule_action_hand_off,
                                (keepAction as RuleAction.HandOff).displayLabel(),
                            ),
                            onSelect = { actionChoice = null },
                        )
                    }
                }
                // Each SIM is a direct action choice, not nested under a
                // separate "A specific SIM" mode row — so exactly one radio in
                // this group is ever filled, never a mode + its selection both.
                items(simOptions, key = { "${it.ref.subscriptionId}|${it.ref.carrierName}|${it.ref.displayName}" }) { option ->
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.USE_SIM && option.ref == selectedSimRef,
                        text = if (option.active) option.label
                        else stringResource(R.string.editor_sim_disabled_suffix, option.label),
                        onSelect = {
                            actionChoice = ActionChoice.USE_SIM
                            simRef = option.ref
                        },
                    )
                }
                item {
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.MATCHING_SIM,
                        text = stringResource(R.string.rule_action_matching_sim),
                        onSelect = { actionChoice = ActionChoice.MATCHING_SIM },
                    )
                }
                // Non-SIM calling accounts (SIP providers, VoIP apps registered
                // with Telecom) sit with the SIMs: they place the call the same
                // way (an account redirect), unlike the hand-off apps below. A
                // stored account that is gone still shows, marked unavailable,
                // so the rule can be kept (it just stays paused).
                items(accountRows, key = { "account|${it.ref.id}" }) { option ->
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.USE_ACCOUNT &&
                            option.ref == selectedAccount?.ref,
                        text = if (option.available) option.label
                        else stringResource(R.string.editor_account_unavailable_suffix, option.label),
                        onSelect = {
                            actionChoice = ActionChoice.USE_ACCOUNT
                            accountChoice = option
                        },
                    )
                }
                // App-to-app hand-off, offered only for installed apps. Applies
                // at call time only when the dialed number is a contact on the
                // app; otherwise the rule skips to the next.
                if (ContactCallApp.WHATSAPP in handOffApps) {
                    item {
                        ChoiceRow(
                            selected = actionChoice == ActionChoice.HANDOFF_WHATSAPP,
                            text = stringResource(R.string.rule_action_hand_off, ContactCallApp.WHATSAPP.label),
                            onSelect = {
                                actionChoice = ActionChoice.HANDOFF_WHATSAPP
                                onContactHandOffPicked()
                            },
                        )
                    }
                    if (actionChoice == ActionChoice.HANDOFF_WHATSAPP && !notificationsEnabled) {
                        item(key = "notifications-hint-whatsapp") {
                            NotificationsHintRow(
                                appLabel = ContactCallApp.WHATSAPP.label,
                                onClick = onEnableNotifications,
                            )
                        }
                    }
                }
                // Dial-intent hand-off (cancel the carrier call, open the app at
                // the number), offered only for installed apps. Skipped hands-free.
                dialHandoffApps.forEach { dialApp ->
                    item(key = "dial|${dialApp.packageName}") {
                        ChoiceRow(
                            selected = actionChoice == ActionChoice.ofDial(dialApp),
                            text = stringResource(R.string.rule_action_hand_off, dialApp.label),
                            onSelect = {
                                actionChoice = ActionChoice.ofDial(dialApp)
                                onRequestNotifications()
                            },
                        )
                    }
                    if (actionChoice == ActionChoice.ofDial(dialApp) && !notificationsEnabled) {
                        item(key = "notifications-hint-dial") {
                            NotificationsHintRow(
                                appLabel = dialApp.label,
                                onClick = onEnableNotifications,
                            )
                        }
                    }
                }
                item {
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.ASK,
                        text = stringResource(R.string.rule_action_ask),
                        onSelect = { actionChoice = ActionChoice.ASK },
                    )
                }
                item {
                    ChoiceRow(
                        selected = actionChoice == ActionChoice.SYSTEM_DEFAULT,
                        text = stringResource(R.string.rule_action_system_default),
                        onSelect = { actionChoice = ActionChoice.SYSTEM_DEFAULT },
                    )
                }
            }

            // Fixed button bar: always visible, never scrolled away.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onDelete != null) {
                    // Delete takes effect at once; the list's Undo bar is the
                    // safety net, so no confirm dialog here.
                    OutlinedButton(onClick = onDelete) {
                        Text(stringResource(R.string.editor_delete))
                    }
                }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.editor_cancel)) }
                Button(
                    onClick = {
                        val matcher =
                            if (matchesAny) RuleMatcher.AnyDestination
                            else destinationMatcher(regions, groups)
                        // The rule and the groups it actually references are saved
                        // in one transaction, so the rule never commits pointing at
                        // an unsaved group and no unreferenced group is left behind:
                        // an "Any country" matcher references none, and a country
                        // matcher only the pending groups still on the rule.
                        val committedGroups =
                            if (matchesAny) emptyList()
                            else pendingGroups.filter { it.id in groups }
                        onSave(
                            committedGroups,
                            EditorDraft(
                                matcher,
                                resolveEditorAction(actionChoice, selectedSimRef, selectedAccount, keepAction),
                            ),
                        )
                    },
                    enabled = isValid(
                        matchesAny, regions, groups, actionChoice, selectedSimRef, simOptions,
                        account = selectedAccount,
                    ),
                ) {
                    Text(stringResource(R.string.editor_save))
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, text: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * One country already on the rule: tappable to re-select the countries branch,
 * with a remove affordance. Indented to align with the radio rows' labels
 * (48dp radio + 8dp gap); dimmed with the same alpha the rules list uses for
 * paused rules while "Any country" is the selected branch.
 */
@Composable
internal fun SelectedCountryRow(
    label: String,
    dimmed: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.4f else 1f)
            .padding(start = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSelect),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = stringResource(R.string.editor_remove_country, label),
            )
        }
    }
}

/**
 * Shown under a selected hand-off action while notifications are off: the
 * "couldn't open <app>" failure notice is a notification, so without it a
 * failed hand-off degrades to a passing toast. Explanation plus an Allow
 * button — the same grant affordance as onboarding's rows, so it reads as
 * actionable, not as a caption. Indented to sit under the action's label
 * (48dp radio + 8dp gap), like the selected-country rows.
 */
@Composable
private fun NotificationsHintRow(appLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.editor_notifications_hint, appLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onClick) { Text(stringResource(R.string.editor_notifications_allow)) }
    }
}

/**
 * The picker's contacts-permission affordance: shown on a blank query when
 * READ_CONTACTS isn't granted, so the "Suggested" bucket is discoverable on its
 * own instead of only filling after the WhatsApp hand-off happens to grant it.
 * Tapping requests the same permission and, on grant, the bucket replaces it.
 */
@Composable
private fun SuggestFromContactsRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Sized like the radio buttons so the label text columns line up.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Filled.Person, contentDescription = null)
        }
        Text(
            text = stringResource(R.string.country_picker_suggest_from_contacts),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Opens the searchable country picker; each pick adds one country to the rule. */
@Composable
internal fun AddCountryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Sized like the radio buttons so the label text columns line up.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
        }
        Text(stringResource(R.string.editor_add_country), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Opens the group editor from within the picker to build a group mid-rule. */
@Composable
private fun AddGroupRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
        }
        Text(stringResource(R.string.groups_add), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Full-screen searchable country picker (a sub-step of the editor). Filters the
 * ~240-country list by name, dial code, ISO code, or alias as the user types;
 * picking one or pressing back returns to the editor.
 */
@Composable
internal fun CountryPickerContent(
    options: List<CountryOptionUi>,
    query: String,
    onQueryChange: (String) -> Unit,
    /** Uppercase ISO regions already on the rule; shown pre-selected. */
    selectedRegions: Set<String>,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    /** Contact-derived countries shown under a "Suggested" header on a blank query. */
    suggested: List<CountryOptionUi> = emptyList(),
    /** When false, a blank query shows a tappable affordance to grant READ_CONTACTS. */
    contactsGranted: Boolean = true,
    onRequestContacts: () -> Unit = {},
    groups: List<CountryGroupOptionUi> = emptyList(),
    selectedGroupIds: Set<String> = emptySet(),
    onSelectGroup: (String) -> Unit = {},
    /** When set, a blank query shows a "New group" row that opens the group editor. */
    onCreateGroup: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val ranked = remember(options, query) { rankCountries(options, query) }
    // The suggested shortcut is for browsing, not searching: once the user
    // types, the ranked results and matching groups already surface the
    // relevant countries, so both the bucket and the grant affordance only
    // show on a blank query.
    val isBlankQuery = remember(query) { foldForSearch(query).isEmpty() }
    val showSuggested = isBlankQuery && suggested.isNotEmpty()
    // Without contacts the bucket can't be built, so offer to grant instead —
    // the same READ_CONTACTS the WhatsApp hand-off uses, one shared grant.
    val showSuggestPrompt = isBlankQuery && suggested.isEmpty() && !contactsGranted
    // Groups sit above the countries: always on a blank query, and for a
    // typed one when it matches the group itself (EU, EEA, Europe, …) OR any
    // member country — searching "France" suggests EU/EEA right where the
    // tap happens.
    // A non-selectable group (soft-deleted, awaiting purge) is kept out of the
    // picker so a new rule can't select it; it still resolves its label for
    // rules that already reference it (see CountryGroupOptionUi.selectable).
    val visibleGroups = remember(groups, query, ranked) {
        matchingGroups(groups, query, ranked).filter { it.selectable }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.editor_country_search_hint)) },
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Contact-derived shortcuts sit above everything, under their own
                // header so their out-of-alphabetical order reads as intentional.
                if (showSuggested) {
                    item(key = "suggested-header") {
                        Text(
                            text = stringResource(R.string.country_picker_suggested),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(suggested, key = { "suggested:${it.regionCode}" }) { option ->
                        ChoiceRow(
                            selected = option.regionCode.uppercase() in selectedRegions,
                            text = option.label,
                            onSelect = { onSelect(option.regionCode) },
                        )
                    }
                }
                if (showSuggestPrompt) {
                    item(key = "suggest-from-contacts") {
                        SuggestFromContactsRow(onClick = onRequestContacts)
                    }
                }
                items(visibleGroups, key = { "group:${it.id}" }) { group ->
                    GroupRow(
                        selected = group.id in selectedGroupIds,
                        label = group.label,
                        description = group.description,
                        onSelect = { onSelectGroup(group.id) },
                    )
                }
                // Make a group right here, mid-rule, without leaving the editor.
                // Blank query only: it's a browse-time action, not a search hit.
                if (onCreateGroup != null && isBlankQuery) {
                    item(key = "new-group") {
                        AddGroupRow(onClick = onCreateGroup)
                    }
                }
                items(ranked, key = { it.regionCode }) { option ->
                    ChoiceRow(
                        selected = option.regionCode.uppercase() in selectedRegions,
                        text = option.label,
                        onSelect = { onSelect(option.regionCode) },
                    )
                }
            }
        }
    }
}

/** A country group in the picker: label plus what the group covers. */
@Composable
private fun GroupRow(
    selected: Boolean,
    label: String,
    description: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal enum class ActionChoice {
    USE_SIM,
    /** A non-SIM calling account (SIP provider); pairs with the account selection. */
    USE_ACCOUNT,
    MATCHING_SIM,
    HANDOFF_WHATSAPP,
    HANDOFF_GOOGLE_VOICE,
    HANDOFF_TEAMS,
    HANDOFF_VIBER,
    HANDOFF_YOLLA,
    HANDOFF_ROAMLESS,
    ASK,
    SYSTEM_DEFAULT,
    ;

    fun toAction(simRef: SimRef?, account: CallingAccountOptionUi? = null): RuleAction = when (this) {
        USE_SIM -> RuleAction.UseSim(simRef!!)
        USE_ACCOUNT -> RuleAction.HandOff.ViaPhoneAccount(account!!.ref, account.label)
        MATCHING_SIM -> RuleAction.UseMatchingCountrySim
        HANDOFF_WHATSAPP -> RuleAction.HandOff.ViaContactApp(ContactCallApp.WHATSAPP)
        HANDOFF_GOOGLE_VOICE -> RuleAction.HandOff.ViaDialIntent(DialHandoffApp.GOOGLE_VOICE)
        HANDOFF_TEAMS -> RuleAction.HandOff.ViaDialIntent(DialHandoffApp.TEAMS)
        HANDOFF_VIBER -> RuleAction.HandOff.ViaDialIntent(DialHandoffApp.VIBER)
        HANDOFF_YOLLA -> RuleAction.HandOff.ViaDialIntent(DialHandoffApp.YOLLA)
        HANDOFF_ROAMLESS -> RuleAction.HandOff.ViaDialIntent(DialHandoffApp.ROAMLESS)
        ASK -> RuleAction.Ask
        SYSTEM_DEFAULT -> RuleAction.SystemDefault
    }

    companion object {
        /** The editor control for a dial-intent hand-off to [app]. */
        fun ofDial(app: DialHandoffApp): ActionChoice = when (app) {
            DialHandoffApp.GOOGLE_VOICE -> HANDOFF_GOOGLE_VOICE
            DialHandoffApp.TEAMS -> HANDOFF_TEAMS
            DialHandoffApp.VIBER -> HANDOFF_VIBER
            DialHandoffApp.YOLLA -> HANDOFF_YOLLA
            DialHandoffApp.ROAMLESS -> HANDOFF_ROAMLESS
        }

        /**
         * The editor control for [action], or null when the editor can't
         * represent it (a hand-off target this version doesn't know) and must
         * preserve the original on save. A new rule (null [action]) defaults
         * to the matching-country SIM.
         */
        fun of(action: RuleAction?): ActionChoice? = when (action) {
            is RuleAction.UseSim -> USE_SIM
            RuleAction.UseMatchingCountrySim -> MATCHING_SIM
            RuleAction.Ask -> ASK
            RuleAction.SystemDefault -> SYSTEM_DEFAULT
            null -> MATCHING_SIM
            is RuleAction.HandOff.ViaPhoneAccount -> USE_ACCOUNT
            is RuleAction.HandOff.ViaContactApp ->
                if (action.app == ContactCallApp.WHATSAPP) HANDOFF_WHATSAPP else null
            is RuleAction.HandOff.ViaDialIntent -> ofDial(action.app)
        }
    }
}

/**
 * The rule the editor's Save produces from [draft]. Editing an existing rule
 * carries its [Rule.enabled] state through — the editor only changes the matcher
 * and action, so a disabled rule must stay disabled rather than silently turning
 * back on (enable/disable is a separate control, the row menu). A new rule starts
 * enabled.
 */
internal fun ruleFromDraft(draft: EditorDraft, target: EditorTarget): Rule =
    Rule(
        draft.matcher,
        draft.action,
        enabled = (target as? EditorTarget.Existing)?.rule?.enabled ?: true,
        // Keep the edited rule's stable id so the save replaces it in place; a
        // new rule keeps blank and is assigned an id when it's persisted.
        id = (target as? EditorTarget.Existing)?.rule?.id.orEmpty(),
    )

/**
 * The action to save: the user's [choice] if they picked one, otherwise the
 * preserved [keepAction] for a rule whose action the editor can't edit.
 */
internal fun resolveEditorAction(
    choice: ActionChoice?,
    simRef: SimRef?,
    account: CallingAccountOptionUi?,
    keepAction: RuleAction?,
): RuleAction =
    choice?.toAction(simRef, account)
        ?: keepAction
        ?: error("Rule editor produced no action")

/**
 * The account rows the editor offers: the live non-SIM calling accounts, plus
 * the rule's [stored] account when it isn't currently registered — shown
 * marked unavailable so the rule can be kept (it just stays paused) instead
 * of forcing a re-link before any other edit can save.
 */
internal fun accountOptions(
    live: List<CallingAccountOptionUi>,
    stored: CallingAccountOptionUi?,
): List<CallingAccountOptionUi> =
    if (stored == null || live.any { it.ref == stored.ref }) live
    else live + stored.copy(available = false)

/**
 * The account option [stored] points at, preferring the live row (freshest
 * label) so a re-save writes the account's current name, not a stale one.
 */
internal fun resolveSelectedAccount(
    stored: CallingAccountOptionUi?,
    options: List<CallingAccountOptionUi>,
): CallingAccountOptionUi? =
    stored?.let { s -> options.firstOrNull { it.ref == s.ref } ?: s }

/**
 * The SIM option that [ref] points at, resolved with the same identity ladder
 * as `resolveSim`: an exact (real) subscription-id match first, then the unique
 * carrier + display-name fallback. So a rule whose SIM id was invalidated to the
 * sentinel or reissued after a restore still highlights (and, on save, re-links
 * to) the rebound option, rather than opening with "A specific SIM" chosen but
 * no visible selection. Falls back to [ref] itself when nothing matches (or is
 * ambiguous), or the first option when [ref] is null, so the selection is never
 * silently emptied.
 */
internal fun resolveSelectedSim(ref: SimRef?, options: List<SimOptionUi>): SimRef? {
    if (ref == null) return options.firstOrNull()?.ref
    options.firstOrNull {
        ref.subscriptionId != SimRef.INVALID_SUBSCRIPTION_ID && it.ref.subscriptionId == ref.subscriptionId
    }?.let { return it.ref }
    val byIdentity = options.filter {
        it.ref.carrierName.trim().equals(ref.carrierName.trim(), ignoreCase = true) &&
            it.ref.displayName.trim().equals(ref.displayName.trim(), ignoreCase = true)
    }
    return byIdentity.singleOrNull()?.ref ?: ref
}

internal fun isValid(
    matchesAny: Boolean,
    regions: List<String>,
    groups: List<String>,
    action: ActionChoice?,
    simRef: SimRef?,
    simOptions: List<SimOptionUi>,
    account: CallingAccountOptionUi? = null,
): Boolean {
    if (!matchesAny && regions.isEmpty() && groups.isEmpty()) return false
    // A specific-SIM action must point at a SIM actually offered here. When the
    // stored SIM can't be resolved to a row (e.g. renamed or removed after a
    // restore, so nothing is selected), require re-linking to a real SIM rather
    // than silently re-saving the paused rule.
    if (action == ActionChoice.USE_SIM && simOptions.none { it.ref == simRef }) return false
    // A calling-account action needs an account picked. (An unavailable stored
    // account still counts — it is offered as a row — so a paused rule can be
    // re-saved intact.)
    if (action == ActionChoice.USE_ACCOUNT && account == null) return false
    return true
}

private fun isContactsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Whether Simmo's notifications can show — the hint hides once they can. A
 * blocked sim_assist channel counts as off even when app-level notifications
 * are on: notify() would be silently suppressed and the failure notice would
 * degrade to the toast, so the hint must keep offering the settings path
 * (Codex on PR #32).
 */
internal fun areNotificationsEnabled(context: Context): Boolean {
    val manager = NotificationManagerCompat.from(context)
    val channelBlocked = manager.getNotificationChannelCompat(SimNotifications.CHANNEL_ID)
        ?.importance == NotificationManagerCompat.IMPORTANCE_NONE
    return manager.areNotificationsEnabled() && !channelBlocked
}

/**
 * Whether the POST_NOTIFICATIONS dialog can be requested at all: the runtime
 * permission exists only from API 33, and re-requesting a granted one is a
 * no-op (notifications can still be off in settings — the hint's settings
 * fallback covers that).
 */
private fun canRequestNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/** Persists the rule's chosen countries across recreation. */
internal val RegionsSaver: Saver<List<String>, Any> = listSaver(
    save = { it },
    restore = { it },
)

/**
 * Persists the pending (picker-created, not-yet-saved) groups across recreation.
 * Each group flattens to `id`, `name`, and a member-count-prefixed region list so
 * the flat string list round-trips without a nested structure.
 */
internal val PendingGroupsSaver: Saver<List<CustomGroup>, Any> = listSaver(
    save = { groups ->
        groups.flatMap { g -> listOf(g.id, g.name, g.regionCodes.size.toString()) + g.regionCodes }
    },
    restore = { flat ->
        buildList {
            var i = 0
            while (i + 3 <= flat.size) {
                val id = flat[i]
                val name = flat[i + 1]
                val count = flat[i + 2].toIntOrNull() ?: 0
                val regions = flat.subList(i + 3, minOf(i + 3 + count, flat.size)).toList()
                add(CustomGroup(id, name, regions))
                i += 3 + count
            }
        }
    },
)

/** Persists the selected calling account across recreation as id + label. */
private val AccountSaver: Saver<CallingAccountOptionUi?, List<String>> = Saver(
    save = { it?.let { a -> listOf(a.ref.id, a.label) } ?: emptyList() },
    restore = { parts ->
        parts.takeIf { it.size == 2 }?.let { CallingAccountOptionUi(PhoneAccountRef(it[0]), it[1]) }
    },
)

/** Persists the selected SIM across recreation as its three identity fields. */
internal val SimRefSaver: Saver<SimRef?, List<String>> = Saver(
    save = { it?.let { r -> listOf(r.subscriptionId.toString(), r.carrierName, r.displayName) } ?: emptyList() },
    restore = { parts ->
        parts.takeIf { it.size == 3 }?.let { SimRef(it[0].toInt(), it[1], it[2]) }
    },
)
