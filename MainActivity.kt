package com.example.wardroberec

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wardroberec.ui.theme.CameraCapture
import com.example.wardroberec.network.UploadHelper
import kotlinx.coroutines.launch
import java.io.File
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wardroberec.api.ClothingItem
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.navigation.NavController

class MainActivity : ComponentActivity() {

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private var hasCameraPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasCameraPermission = isGranted
            if (!isGranted) {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        // Request permission initially or whenever needed
        requestCameraPermission()
        setContent {
            AppNavigation()
        }
    }

    // -------------------- NAVIGATION --------------------
    @Composable
    fun AppNavigation() {
        val navController = rememberNavController()
        val outfitViewModel: OutfitViewModel = viewModel()

        NavHost(
            navController = navController,
            startDestination = "home"   // new start page with 3 sections
        ) {
            // HOME PAGE with 3 sections
            composable("home") {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(onClick = { navController.navigate("wardrobe") }) {
                        Text("Open Wardrobe")
                    }
                    Button(onClick = { navController.navigate("camera") }) {
                        Text("Open Camera")
                    }
                    Button(onClick = { navController.navigate("preferences") }) {
                        Text("Recommendations")
                    }
                }
            }

            // PREFERENCES PAGE with dropdowns
            composable("preferences") {
                PreferencesScreen(
                    viewModel = outfitViewModel,
                    onBack = { navController.popBackStack() },
                    onSubmit = { navController.navigate("recommendation") }
                )
            }

            // CAMERA PAGE
            composable("camera") {
                CameraPage(onBack = { navController.popBackStack() })
            }

            // WARDROBE PAGE
            composable("wardrobe") {
                WardrobeApp(viewModel = outfitViewModel)
            }

            // FINAL RECOMMENDATION PAGE
            composable("preferences") {
                PreferencesScreen(
                    viewModel = outfitViewModel,
                    onBack = { navController.popBackStack() },
                    onSubmit = { navController.navigate("recommendation") }
                )
            }
            composable("recommendation") {
                RecommendationScreen(viewModel = outfitViewModel, navController = navController)
            }
        }
    }

    // -------------------- PREFERENCES SCREEN --------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PreferencesScreen(
        viewModel: OutfitViewModel,
        onBack: () -> Unit,
        onSubmit: () -> Unit
    ) {
        var category by remember { mutableStateOf("") }
        var catExpanded by remember { mutableStateOf(false) }

        var color by remember { mutableStateOf("") }
        var colorExpanded by remember { mutableStateOf(false) }

        var material by remember { mutableStateOf("") }
        var matExpanded by remember { mutableStateOf(false) }

        var occasion by remember { mutableStateOf("") }
        var occExpanded by remember { mutableStateOf(false) }

        var keywordsInput by remember { mutableStateOf("") }

        // computed list of keywords (up to 3, trimmed)
        val keywordsList = keywordsInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)

        val categoryOptions = listOf("Top", "Bottom", "Footwear", "Accessories", "Dress", "Jacket")
        val colorOptions = listOf("Black", "White", "Red", "Blue", "Green", "Yellow", "Orange", "Purple", "Pink", "Brown")
        val materialOptions = listOf("Cotton", "Denim", "Linen", "Silk", "Wool", "Leather", "Synthetic")
        val occasionOptions = listOf("Casual", "Formal", "Work", "Party Wear", "Business Casual", "Athleisure", "Boho", "Vintage", "Streetwear", "Preppy", "Minimalist", "Festive", "Travel")

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Top bar with back button
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(8.dp))

            // CATEGORY DROPDOWN (optional)
            ExposedDropdownMenuBox(
                expanded = catExpanded,
                onExpandedChange = { catExpanded = !catExpanded }
            ) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Category (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = catExpanded,
                    onDismissRequest = { catExpanded = false }
                ) {
                    categoryOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                category = option
                                catExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // COLOR DROPDOWN (optional)
            ExposedDropdownMenuBox(
                expanded = colorExpanded,
                onExpandedChange = { colorExpanded = !colorExpanded }
            ) {
                OutlinedTextField(
                    value = color,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Color (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = colorExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = colorExpanded,
                    onDismissRequest = { colorExpanded = false }
                ) {
                    colorOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                color = option
                                colorExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // MATERIAL DROPDOWN (optional)
            ExposedDropdownMenuBox(
                expanded = matExpanded,
                onExpandedChange = { matExpanded = !matExpanded }
            ) {
                OutlinedTextField(
                    value = material,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Material (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = matExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = matExpanded,
                    onDismissRequest = { matExpanded = false }
                ) {
                    materialOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                material = option
                                matExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // OCCASION DROPDOWN (mandatory)
            ExposedDropdownMenuBox(
                expanded = occExpanded,
                onExpandedChange = { occExpanded = !occExpanded }
            ) {
                OutlinedTextField(
                    value = occasion,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Occasion (required)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = occExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = occExpanded,
                    onDismissRequest = { occExpanded = false }
                ) {
                    occasionOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                occasion = option
                                occExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // KEYWORDS INPUT
            OutlinedTextField(
                value = keywordsInput,
                onValueChange = {
                    // enforce 3 max keywords
                    if (it.split(",")
                            .map { word -> word.trim() }
                            .filter { word -> word.isNotEmpty() }
                            .size <= 3
                    ) {
                        keywordsInput = it
                    }
                },
                label = { Text("Style Keywords (max 3, comma separated)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            // SUBMIT BUTTON
            Button(
                onClick = {
                    if (occasion.isNotEmpty()) {
                        viewModel.setPreferences(
                            category = category,
                            color = color,
                            material = material,
                            occasion = occasion,
                            keywords = keywordsList
                        )
                        onSubmit()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = occasion.isNotEmpty()
            ) {
                Text("Get Recommendation")
            }
        }
    }

    // -------------------- RATINGS BOTTOM SHEET --------------------
    @Composable
    fun RatingSheetContent(
        currentStars: Int,
        onStarSelected: (Int) -> Unit,
        onSubmit: () -> Unit,
        onCancel: () -> Unit,
        submitting: Boolean = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Rate this outfit",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                (1..5).forEach { i ->
                    IconButton(onClick = { onStarSelected(i) }) {
                        Icon(
                            imageVector = if (i <= currentStars) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Star $i",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    onClick = onSubmit,
                    enabled = !submitting
                ) {
                    Text("Submit")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel, enabled = !submitting) {
                    Text("Cancel")
                }
            }

            if (submitting) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }

    // -------------------- CAMERA PAGE --------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CameraPage(onBack: () -> Unit) {
        val context = LocalContext.current

        DisposableEffect(Unit) {
            onDispose {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
        val scope = rememberCoroutineScope()
        var capturedFile by remember { mutableStateOf<File?>(null) }

        // Dropdown states
        var category by remember { mutableStateOf("") }
        var color by remember { mutableStateOf("") }
        var material by remember { mutableStateOf("") }
        var occasion by remember { mutableStateOf("") }

        val categoryOptions = listOf("Top", "Bottom", "Footwear", "Accessories", "Dress", "Jacket")
        val colorOptions = listOf("Black", "White", "Red", "Blue", "Green", "Yellow", "Orange", "Purple", "Pink", "Brown")
        val materialOptions = listOf("Cotton", "Denim", "Linen", "Silk", "Wool", "Leather", "Synthetic")
        val occasionOptions = listOf("Casual", "Formal", "Work", "Party Wear", "Business Casual", "Athleisure", "Boho", "Vintage", "Streetwear", "Preppy", "Minimalist", "Festive", "Travel")

        // Dropdown expanded states
        var catExpanded by remember { mutableStateOf(false) }
        var colorExpanded by remember { mutableStateOf(false) }
        var matExpanded by remember { mutableStateOf(false) }
        var occExpanded by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // Top bar with close button
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            if (capturedFile == null) {
                CameraCapture { file ->
                    capturedFile = file
                    Log.d("CameraCapture", "Captured image saved at: ${file.absolutePath}")
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(capturedFile),
                        contentDescription = "Captured photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // CATEGORY DROPDOWN
                    ExposedDropdownMenuBox(
                        expanded = catExpanded,
                        onExpandedChange = { catExpanded = !catExpanded }
                    ) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Category") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = catExpanded,
                            onDismissRequest = { catExpanded = false }
                        ) {
                            categoryOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        category = option
                                        catExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // COLOR DROPDOWN
                    ExposedDropdownMenuBox(
                        expanded = colorExpanded,
                        onExpandedChange = { colorExpanded = !colorExpanded }
                    ) {
                        OutlinedTextField(
                            value = color,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Color") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = colorExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = colorExpanded,
                            onDismissRequest = { colorExpanded = false }
                        ) {
                            colorOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        color = option
                                        colorExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // MATERIAL DROPDOWN
                    ExposedDropdownMenuBox(
                        expanded = matExpanded,
                        onExpandedChange = { matExpanded = !matExpanded }
                    ) {
                        OutlinedTextField(
                            value = material,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Material") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = matExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = matExpanded,
                            onDismissRequest = { matExpanded = false }
                        ) {
                            materialOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        material = option
                                        matExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // OCCASION DROPDOWN
                    ExposedDropdownMenuBox(
                        expanded = occExpanded,
                        onExpandedChange = { occExpanded = !occExpanded }
                    ) {
                        OutlinedTextField(
                            value = occasion,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Occasion") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = occExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = occExpanded,
                            onDismissRequest = { occExpanded = false }
                        ) {
                            occasionOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        occasion = option
                                        occExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(onClick = { capturedFile = null }) {
                            Text("Retake")
                        }

                        Button(
                            enabled = category.isNotBlank() && color.isNotBlank()
                                    && material.isNotBlank() && occasion.isNotBlank(),
                            onClick = {
                                capturedFile?.let { file ->
                                    scope.launch {
                                        val result = UploadHelper.uploadClothing(
                                            file, category, color, material, occasion
                                        )
                                        result.onSuccess {
                                            Log.d("UploadDebug", "Uploaded successfully: $it")
                                            onBack()
                                        }.onFailure {
                                            Log.e("UploadDebug", "Upload failed", it)
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Upload")
                        }
                    }
                }
            }
        }
    }

    // -------------------- WARDROBE PAGE --------------------
    @Composable
    fun FilterChips(
        title: String,
        options: List<String>,
        selectedOptions: List<String>,
        onSelectionChange: (List<String>) -> Unit,
        addSelection: (String) -> Unit,
        removeSelection: (String) -> Unit
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            // Using bodyLarge style for title text
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )

            // Row for displaying the filter chips
            Row(
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start)
                    .fillMaxWidth()
            ) {
                options.forEach { option ->
                    val isSelected = selectedOptions.contains(option)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isSelected) {
                                removeSelection(option)
                            } else {
                                addSelection(option)
                            }
                            onSelectionChange(selectedOptions)  // Update the selected options list
                        },
                        label = { Text(option) },
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }

    // -------------------- REC PAGE --------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun RecommendationScreen(
        viewModel: OutfitViewModel,
        navController: NavController
    ) {
        val scope = rememberCoroutineScope()
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true // prevents half-expanded state
        )

        val recommendation = viewModel.recommendation
        var isLoading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            viewModel.fetchRecommendation()
            isLoading = false
        }
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { sheetState.hide() }
            },
            sheetState = sheetState
        ) {
            RatingSheetContent(
                currentStars = viewModel.selectedStars,
                onStarSelected = { viewModel.setStars(it) },
                onSubmit = {
                    val itemIds: Map<String, Int> = recommendation?.items
                        ?.mapNotNull { (k, v) -> v?.id?.let { id -> k to id } }
                        ?.toMap()
                        ?: emptyMap()

                    scope.launch {
                        viewModel.submitRating(
                            outfitId = recommendation?.outfitId ?: "",
                            items = itemIds,
                            userId = null
                        ) { success, errMsg ->
                            if (success) {
                                scope.launch { sheetState.hide() }
                            } else {
                                // TODO show snackbar
                            }
                        }
                    }
                },
                onCancel = {
                    scope.launch { sheetState.hide() }
                },
                submitting = viewModel.ratingSubmitting
            )
        }
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Loading...")
            }
        } else {
            val items = recommendation?.items
            if (items.isNullOrEmpty() || items.values.all { it == null }) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "No clothing items found for this request.")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { navController.navigate("home") }) {
                            Text(text = "Go Back")
                        }
                    }
                }
            } else {
                val displayOrder = listOf("top", "bottom", "footwear", "accessories", "dress", "jacket")

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Occasion: ${recommendation.occasion}")
                    Text(text = "Weather: ${recommendation.weather}")
                    Spacer(modifier = Modifier.height(8.dp))

                    displayOrder.forEach { slot ->
                        val item = items[slot]
                        if (item != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = item.imageUrl,
                                    contentDescription = slot,
                                    modifier = Modifier.size(80.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = slot.replaceFirstChar { it.uppercase() })
                                    Text(text = "Color: ${item.color}, Material: ${item.material}")
                                    item.labels?.let { lbls ->
                                        if (lbls.isNotEmpty()) {
                                            Text(text = "Labels: ${lbls.joinToString(", ")}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = { scope.launch { sheetState.show() } }
                    ) {
                        Text("Rate This Outfit")
                    }
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun PreviewFilterChips() {
        var selectedOptions by remember { mutableStateOf(listOf<String>()) }

        // Mock the add/remove functions for preview
        FilterChips(
            title = "Select Options",
            options = listOf("Option 1", "Option 2", "Option 3", "Option 4"),
            selectedOptions = selectedOptions,
            onSelectionChange = { selectedOptions = it },
            addSelection = { selectedOptions = selectedOptions + it }, // Mock add selection
            removeSelection = { selectedOptions = selectedOptions - it } // Mock remove selection
        )
    }

    @Composable
    fun WardrobeApp(viewModel: OutfitViewModel) {
        var wardrobeItems by remember { mutableStateOf(listOf<ClothingItem>()) }
        val coroutineScope = rememberCoroutineScope()  // get coroutine scope

        LaunchedEffect(Unit) {
            wardrobeItems = viewModel.fetchWardrobeItems("", "")
        }

        if (wardrobeItems.isEmpty()) {
            Text("No clothing items found", modifier = Modifier.padding(16.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(wardrobeItems, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box {
                            AsyncImage(
                                model = item.imageUrl, // <-- use camelCase property
                                contentDescription = item.category,
                                modifier = Modifier
                                    .height(180.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val success = viewModel.deleteClothingItem(item.id)
                                        if (success) {
                                            wardrobeItems = viewModel.fetchWardrobeItems("", "")
                                        } else {
                                            // Optionally show an error message
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.White.copy(alpha = 0.7f), CircleShape)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = item.color,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.category,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = item.occasion,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                hasCameraPermission = true
            }
            shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA) -> {
                // Show UI explaining why you need the permission before requesting again (optional)
                Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
            else -> {
                // Directly request for permission
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

}


