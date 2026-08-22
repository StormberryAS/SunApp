package no.stormberry.sunapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.stormberry.sunapp.cities.City
import no.stormberry.sunapp.cities.CityTable
import no.stormberry.sunapp.ui.components.SectionLabel

/**
 * Where the bundled city catalogue has got to.
 *
 * The screen has to render before the answer is known, because parsing 25,007
 * rows off the main thread is the whole point: a few tens of milliseconds is
 * nothing to wait for in the background and everything to wait for before the
 * first frame. So loading is a state the UI shows, not a condition it hides.
 */
sealed interface CatalogueState {
    /** Being parsed on a background thread. The search field is not yet usable. */
    data object Loading : CatalogueState

    data class Ready(val table: CityTable) : CatalogueState

    /**
     * The asset is missing or malformed, which means a broken build rather than
     * anything the user did. Coordinates still work, so the app stays usable and
     * says so instead of showing an empty search field that never matches.
     */
    data class Failed(val message: String) : CatalogueState
}

/**
 * The city half of the location input: a search field, up to eight suggestions,
 * and the current selection.
 *
 * Ranking, folding and the eight-row limit all live in
 * [no.stormberry.sunapp.cities.CitySearch], which the web app's dropdown is a
 * port of. Nothing here re-ranks or re-filters, so a place that is findable on
 * sun.stormberry.as is findable here with the same keystrokes.
 */
@Composable
fun CityPicker(
    state: CatalogueState,
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<City>,
    onSelect: (City) -> Unit,
    selectedLabel: String?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel("Search city or country")
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = state is CatalogueState.Ready,
            singleLine = true,
            placeholder = {
                Text(
                    text = when (state) {
                        is CatalogueState.Ready -> "e.g. Oslo, Tokyo, São Paulo"
                        CatalogueState.Loading -> "Loading the city catalogue…"
                        is CatalogueState.Failed -> "City search unavailable"
                    },
                    color = Sun.TextMuted,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Clear the search text",
                            ) { onQueryChange("") }
                            .padding(12.dp),
                    ) {
                        // A glyph rather than a Material icon: this app does not
                        // depend on material-icons, and pulling in the artefact for
                        // one multiplication sign would be a poor trade.
                        Text("×", color = Sun.TextSecondary, fontSize = 20.sp)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                // Place names are proper nouns, and the search folds case anyway,
                // so capitalising words costs nothing and matches what people type.
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Search,
            ),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Sun.TextPrimary,
                unfocusedTextColor = Sun.TextPrimary,
                disabledTextColor = Sun.TextMuted,
                focusedContainerColor = Sun.Surface,
                unfocusedContainerColor = Sun.Surface,
                disabledContainerColor = Sun.Surface,
                focusedBorderColor = Sun.BorderActive,
                unfocusedBorderColor = Sun.Border,
                disabledBorderColor = Sun.Border,
                cursorColor = Sun.Gold,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (state is CatalogueState.Failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Sun.Rose,
            )
        }

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SuggestionList(suggestions = suggestions, onSelect = onSelect)
        } else if (state is CatalogueState.Ready && query.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No match. Try the country, or switch to coordinates.",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.TextMuted,
            )
        }

        if (selectedLabel != null) {
            Spacer(Modifier.height(12.dp))
            SelectedRow(label = selectedLabel, onClear = onClear)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            // The web app makes this a mailto link. Here it is plain text on
            // purpose: firing an intent would hand the address to whatever app
            // claims it, and an app whose headline is "nothing leaves the device"
            // should not do that behind a single tap.
            text = "Missing a place? Write to info@stormberry.as and it will be " +
                "added to the shared catalogue.",
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextMuted,
        )
    }
}

@Composable
private fun SuggestionList(suggestions: List<City>, onSelect: (City) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    // A plain Column, not a LazyColumn: CitySearch returns at most eight rows, and
    // a lazy list nested in the screen's vertical scroll would need a fixed height
    // to measure at all.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Sun.Surface, shape)
            .border(1.dp, Sun.Border, shape),
    ) {
        suggestions.forEachIndexed { index, city ->
            if (index > 0) HorizontalDivider(color = Sun.Border)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Show sun times for ${city.name}",
                    ) { onSelect(city) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = city.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Sun.TextPrimary,
                    )
                    Text(
                        text = city.country,
                        style = MaterialTheme.typography.bodySmall,
                        color = Sun.TextMuted,
                    )
                }
                Text(
                    text = city.tz,
                    style = MaterialTheme.typography.bodySmall,
                    color = Sun.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SelectedRow(label: String, onClear: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Sun.CardHover, shape)
            .border(1.dp, Sun.BorderActive, shape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Sun.Gold,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Clear the selected place",
                ) { onClear() }
                .padding(12.dp),
        ) {
            Text("×", color = Sun.TextSecondary, fontSize = 20.sp)
        }
    }
}
