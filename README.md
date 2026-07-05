# SPECURA: Smart Photo-based Evaluation of Construction Units Recognition and Assessment

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
[![AI Engine](https://img.shields.io/badge/AI_Engine-MobileCLIP%20%2F%20ONNX-0052CC?style=flat-square)](https://onnxruntime.ai/)
[![Vision](https://img.shields.io/badge/Computer_Vision-OpenCV-5C3EE8?style=flat-square&logo=opencv)](https://opencv.org/)
[![Database](https://img.shields.io/badge/Database-Room%20%2F%20SQLite-003B5C?style=flat-square&logo=sqlite)](https://developer.android.com/training/data-storage/room)

**SPECURA** is an offline-capable, AI-assisted Android mobile application engineered for preliminary architectural material identification and visible surface condition assessment. Developed using advanced computer vision and lightweight on-device deep learning, the system bridges the gap between expert structural knowledge and non-expert stakeholders (such as homeowners, property managers, and civil engineering students), allowing them to perform immediate, on-site material analysis and defect identification from a single photograph.

---

## 🚀 Key Features

* **100% Edge-Based & Offline Capability:** Executes all machine learning inference, computer vision calculations, and historical logging operations completely on-device. This guarantees absolute data privacy, eliminates network latencies, and ensures seamless operation in remote construction sites or sub-surface structures with zero internet connectivity.
* **Intelligent Material Recognition:** Utilizes a lightweight, transformer-based vision-language foundation model (**MobileCLIP**) optimized for mobile systems via the **ONNX Runtime Engine** to handle zero-shot semantic material classification across primary structural categories: **Concrete, Wood, Metal, and Brick**.
* **Advanced Surface Defect Analysis:** Employs a custom **OpenCV image processing pipeline** to isolate regional structural anomalies, computing edge densities, textures, and anomalies corresponding to visible deterioration profiles like cracks, spalling, weathering, and rust/corrosion.
* **Calibrated Severity Grading Matrix:** Automatically derives a structural condition score bounded between `0.00` and `1.00`, grouping structural assessments into three actionable intervention tiers:
  * 🟢 **Minor Damage (0.00 – 0.29):** Negligible surface wear. Regular tracking suggested; no structural mitigation needed.
  * 🟡 **Moderate Damage (0.30 – 0.59):** Noticeable defects or superficial fissures present. Professional diagnostic inspection recommended.
  * 🔴 **Severe Damage (0.60 – 1.00):** Extensive material compromises, structural deep cracks, or massive oxidation. Immediate safety validation and structural repair strongly advised.
* **Temporal Trend Tracking:** Implements the **Room Database Framework (SQLite architecture)** coupled with persistent localized key-value historical states via encrypted internal tracking mechanisms. The application cross-references sequential inspections over matching location tags to automatically flag material stability trends over time (`NEW`, `STABLE`, or `WORSENING`).
* **Automated Decision Support:** Translates abstract raw tensor probabilities and OpenCV pixel counts into immediate human-readable text blocks, offering structured usability guidelines and high-level material maintenance recommendations.

---

## 🛠️ System Architecture & Execution Flow

[ High-Resolution Camera Capture / Local Image Import ]
│
▼
[ OpenCV Preprocessing Pipeline ]
(Grayscale Conversion, Bilateral Noise Reduction,
Adaptive Edge Detection & ROI Extraction)
│
▼
[ MobileCLIP Vision-Language Embeddings ]
(ONNX Runtime Edge Engine Core - Vector Dot-Product)
│
▼
[ Defect & Severity Processor ]
(Quantification of Defective Pixel Density + Weight Map)
│
▼
[ Local Layer Data Persistence ]
(Room SQLite Matrix + Location Tag Chronological History)
│
▼
[ Rich UI Client-Side Dashboard ]
(Real-Time Category Render, Score Gauge & Practical Advice)


---

## 💻 Tech Stack & Dependencies

* **Development Paradigm & SDKs:** Kotlin, Android Jetpack Utilities (Architecture Components, ViewModel, LiveData)
* **Computer Vision Kernel:** OpenCV Android SDK (Local C++ bound binaries for rapid matrix manipulations)
* **Neural Runtime Infrastructure:** ONNX Runtime Mobile (`ai.onnxruntime`) for efficient hardware-accelerated transformer evaluation
* **Local Persistence Layer:** Room Database (SQLite object mapping abstraction) for chronological entity management
* **Data Interchange & Serialization:** Google Gson for swift structured local state mapping

---

## 📊 Threshold Assessment Metric Matrix

| Score Bounds | Numerical Mapping | Color Coding | Practical Structural Guidance |
| :--- | :--- | :--- | :--- |
| **0.00 – 0.29** | Low Profile Damage | 🟢 Green | Normal superficial aging. Surface is healthy; perform standard aesthetic upkeep. |
| **0.30 – 0.59** | Medium Profile Damage | 🟡 Yellow | Notable wear profiles detected. Plan structural verification inspections within standard maintenance cycles. |
| **0.60 – 1.00** | High Profile Damage | 🔴 Red | High structural integrity risks identified. Isolate regional use and deploy immediate expert stabilization teams. |

---

## 📋 Technical Requirements

### Client Mobile Specifications
* **Operating System:** Android 12 (API Level 31) up to Android 14 (API Level 34)
* **Hardware Architecture:** ARM64-v8a processor topology (Octa-core layouts preferred for low-latency neural calculations)
* **Memory Capacity:** Minimum 4 GB RAM (6 GB+ recommended for large resolution imagery buffers)
* **Sensor Payload:** Minimum 12MP Camera configuration with enabled focus tracking
* **Internal Storage Allocations:** ~800 MB - 1 GB available disk space (allocating for local neural weights and image processing caches)

### Engineering Workspace Environments
* **Development IDE:** Android Studio Jellyfish / Koala (or newer stable release patches)
* **Target Build Settings:** Compile SDK: `34` | Target SDK: `34` | Minimum SDK: `31`
* **Build Automation Platform:** Gradle (Kotlin DSL configuration framework)

---

## 🔧 Installation & Engineering Setups

### Project Environment Preparation
1. Clone the project code distribution structure down to your local engineering terminal configuration:
   ```bash
   git clone [https://github.com/Olive568/Specura.git](https://github.com/Olive568/Specura.git)
Launch your instance of Android Studio and load the newly cloned project repository root folder.

Allow the automated system dependencies to pull down via the initial Gradle sync procedure.

Ensure the OpenCV Android SDK native references are correctly initialized via your system project build files.

Deploying the Pre-trained Inference Model Weights
To maintain complete zero-network reliability, the precompiled model assets must be embedded natively inside the application distribution binary:

Export or locate your trained MobileCLIP model package structure translated into an optimized ONNX binary file (typically named mobileclip.onnx).

Map into your local project workspace subdirectory path:

app/src/main/assets/
Paste the target .onnx inference payload along with any associated label maps directly inside this folder asset bundle before triggering an execution build step.

Local Client Debug Execution (Sideload Testing)
Turn on Developer Options and enable USB Debugging inside your destination target Android physical testing hardware terminal.

Link the mobile target hardware terminal structure via an appropriate physical tether or wireless ADB configuration.

Select the Run 'app' control node icon within the central toolbar of Android Studio (Shift + F10).

Alternatively, use terminal commands to generate the debug package:

Bash
./gradlew assembleDebug
Extract the compiled output package found within app/build/outputs/apk/debug/ and manually flash the asset file to complete on-device installation.

📱 Operational Step-by-Step Guide
Launch Control Panel: Boot up the application interface to land on the primary operational overview panel showing past scanning metrics, historic trending logs, and inspection points.

Surface Extraction Execution: Select the main camera deployment controller to wake the onboard imaging hardware. Position the viewport perpendicular to the target construction structural frame element to limit optical distortion patterns. Alternatively, press the gallery import tool to load an existing image asset.

Automated Vision Analysis: The underlying engine runs local OpenCV matrix filtering processes to balance dynamic range variations, map high-frequency edge vectors, and present normalized color regions into the ONNX execution runtime for deep semantic processing.

Assessment Reports Evaluation: Review the generated real-time structural inspection report matrix providing:

Categorized Construction Material Classification

Isolated Material Superficial Damage and Fault Types

Calibrated Defect Severity Score and Condition Category Tag

Executable Strategic Maintenance Guidance and Security Information

Historical Serialization Tracking: Input specific localized naming descriptors or spatial reference points to save the complete inspection event record context into the Room SQL database cache for proactive timeline tracking.

🎓 Academic Foundation & Project Credits
This mobile software application is the technical product of academic research and implementation developed as an undergraduate capstone thesis project toward the completion of the degree requirements for the program Bachelor of Science in Information Technology within the College of Information Technology and Engineering at Southville International School and Colleges (Las Piñas City, Metro Manila, Philippines).

Principal Developer / System Architect: Luis Oliver C. Labapis

Research & Development Mentor: MIT. Gilbert Clause H. Magallanes (Thesis Adviser)

Institutional Academic Oversight: Dr. Aris E. Ignacio (Dean, College of Information Technology and Engineering)

📄 Terms of Use & Licensing
This software suite and its source modules are distributed primarily as an open academic research artifact. For commercial redistribution inquiries, advanced module enhancement access, or framework integration permissions, please reach out to the original development author or corresponding institutional research department representatives.
