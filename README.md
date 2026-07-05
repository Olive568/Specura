Specura

AI-powered construction material recognition and damage assessment using MobileCLIP, OpenCV, and on-device machine vision.

Specura is an Android application that identifies construction materials from a single photo and performs lightweight visual damage assessment entirely on-device. Built for the Architecture, Engineering, and Construction (AEC) industry, it assists students, homeowners, and professionals by providing instant material recognition, severity analysis, and inspection history without requiring an internet connection.

This project was developed as my undergraduate capstone under the Architectural Recognition of Materials System (A.R.M.S.) framework.

Features
Material recognition using MobileCLIP
Detects visible surface damage
Cracks
Rust
Surface deterioration
Severity grading
Minor
Moderate
Severe
Confidence-based predictions
Scan history and condition tracking
Fully offline inference using ONNX Runtime
Android-first deployment
Lightweight and optimized for mobile devices
Supported Materials

Current supported materials include:

Concrete
Brick
Wood
Metal

The system focuses on raw or unpainted materials, where surface texture remains visible for accurate recognition.

How It Works
User captures image
        │
        ▼
Center Crop (224×224)
        │
        ▼
MobileCLIP Image Encoder
        │
        ▼
Image-Text Similarity
        │
        ▼
Material Prediction
        │
        ▼
OpenCV Damage Analysis
        │
        ▼
Severity Calculation
        │
        ▼
Recommendations + History
Tech Stack
Component	Technology
Language	Kotlin
Platform	Android
AI Model	MobileCLIP
Runtime	ONNX Runtime Mobile
Computer Vision	OpenCV
Image Processing	OpenCV + ROI Analysis
Local Storage	Room Database
Build Tool	Android Studio
AI Pipeline

Unlike traditional CNN classifiers, Specura uses Vision-Language Models.

The application compares an image embedding against predefined text prompts such as:

Concrete surface
Cracked concrete
Clean metal
Rusted metal
Damaged wood
Intact brick

The highest cosine similarity becomes the predicted material and condition.

Additional OpenCV processing estimates structural damage using:

Edge Density
ROI Extraction
Contour Analysis
Material-aware scoring

These are combined into a final severity score.

Example Output
Material:
Concrete

Condition:
Cracked

Confidence:
96.8%

Severity:
Moderate

Recommendation:
Monitor crack progression.
Schedule inspection if crack width increases.
Why Specura?

Most existing recognition systems like Google Lens identify objects.

Specura is designed specifically for the AEC industry, providing:

Construction material recognition
Damage interpretation
Severity estimation
Inspection history
Offline operation

It serves as a practical inspection assistant rather than a general-purpose image recognizer.

Research Validation

The project was refined through interviews with practicing architects from the Philippine AEC industry to ensure that the workflow reflects real-world architectural practice.

Industry feedback influenced:

supported materials
workflow design
system limitations
inspection process
severity interpretation
Current Limitations
Supports only selected construction materials
Painted or coated materials may reduce recognition accuracy
Detects only visible surface defects
Cannot detect internal structural failures
Lighting and image quality affect performance
Future Improvements
Additional construction materials
Painted material recognition
Segmentation-based material localization
Instance detection using YOLO
Thermal image support
BIM integration
Automatic inspection report generation
Cloud synchronization

Repository Structure
Specura/
├── app/
├── models/
│   ├── mobileclip.onnx
│   └── tokenizer.json
├── opencv/
├── ui/
├── data/
├── inference/
├── utils/
└── README.md
Author

Luis Oliver C. Labapis

BS Information Technology (Artificial Intelligence)


LinkedIn: [https://linkedin.com/in/yourprofile](https://www.linkedin.com/in/luis-oliver-labapis-0a5434322/)

License

This project is released for educational and research purposes.
