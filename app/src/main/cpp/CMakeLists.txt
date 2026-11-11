# Native libraries use pinned source revisions. Prepare llama.cpp with the
# developer script before Gradle; runtime recognition never fetches code/models.
cmake_minimum_required(VERSION 3.22.1)
project(xnotests C CXX)

# No build-id note: it hashes the pre-strip output (build paths included), so it
# varies across build hosts and breaks F-Droid's reproducible-build verification.
# max-page-size=16384 keeps the libs loadable on 16 KB page-size devices.
set(XNOTES_LINK_OPTIONS -Wl,--gc-sections -Wl,--build-id=none -Wl,-z,max-page-size=16384)

set(GRAMMAR_SOURCES
    grammars/asm/parser.c
    grammars/bash/parser.c
    grammars/bash/scanner.c
    grammars/c/parser.c
    grammars/cpp/parser.c
    grammars/cpp/scanner.c
    grammars/css/parser.c
    grammars/css/scanner.c
    grammars/diff/parser.c
    grammars/dockerfile/parser.c
    grammars/dockerfile/scanner.c
    grammars/go/parser.c
    grammars/html/parser.c
    grammars/html/scanner.c
    grammars/java/parser.c
    grammars/javascript/parser.c
    grammars/javascript/scanner.c
    grammars/json/parser.c
    grammars/kotlin/parser.c
    grammars/kotlin/scanner.c
    grammars/lua/parser.c
    grammars/lua/scanner.c
    grammars/php/parser.c
    grammars/php/scanner.c
    grammars/python/parser.c
    grammars/python/scanner.c
    grammars/rust/parser.c
    grammars/rust/scanner.c
    grammars/toml/parser.c
    grammars/toml/scanner.c
    grammars/typescript/parser.c
    grammars/typescript/scanner.c
    grammars/xml/parser.c
    grammars/xml/scanner.c
    grammars/yaml/parser.c
    grammars/yaml/scanner.c
)

# libxnotests: the tree-sitter runtime, the bundled grammars and a thin JNI bridge.
add_library(xnotests SHARED
    tree-sitter/lib/src/lib.c
    ${GRAMMAR_SOURCES}
    ts_jni.c
)
set_target_properties(xnotests PROPERTIES C_STANDARD 11)
# Applied to every build type (AGP release uses RelWithDebInfo, not Release).
target_compile_options(xnotests PRIVATE
    -Os -fvisibility=hidden -ffunction-sections -fdata-sections)

target_include_directories(xnotests PRIVATE
    tree-sitter/lib/include
    tree-sitter/lib/src
)

# Each grammar carries its own tree_sitter/parser.h; scope it to that grammar's
# sources so the copies never collide.
foreach(src ${GRAMMAR_SOURCES})
    get_filename_component(dir ${src} DIRECTORY)
    set_source_files_properties(${src} PROPERTIES
        INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/${dir}")
endforeach()

target_link_options(xnotests PRIVATE ${XNOTES_LINK_OPTIONS})

# libxnotespdf: PDFium and its JNI bridge. pdfium.cmake (tools/pdfium-sync.sh)
# gives each PDFium library upstream's own flags, so none set here leak in.
set(PDFIUM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/pdfium)
include(pdfium.cmake)

add_library(xnotespdf SHARED pdf_jni.cpp)
target_include_directories(xnotespdf PRIVATE ${PDFIUM_DIR})
target_compile_definitions(xnotespdf PRIVATE XNOTES_PDFIUM_REVISION="${PDFIUM_REVISION}")
target_compile_options(xnotespdf PRIVATE -std=c++20 -fno-exceptions -fno-rtti -Os
    -fvisibility=hidden -ffunction-sections -fdata-sections
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}/=")
target_link_libraries(xnotespdf PRIVATE ${PDFIUM_LIBRARIES} jnigraphics log)
# Only the JNI entry points are exported; FreeType would export its whole API.
set(XNOTESPDF_EXPORTS ${CMAKE_CURRENT_SOURCE_DIR}/pdf_jni.map)
target_link_options(xnotespdf PRIVATE ${XNOTES_LINK_OPTIONS}
    "-Wl,--version-script=${XNOTESPDF_EXPORTS}")
set_target_properties(xnotespdf PROPERTIES LINK_DEPENDS ${XNOTESPDF_EXPORTS})

# CPU-only GLM-OCR. Source/weights are prepared on the developer machine;
# the recognizer has no downloader or HTTP server. Optional package downloads
# are handled separately and never carry image data.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../build/references/llama.cpp)
if(NOT EXISTS "${LLAMA_DIR}/include/llama.h")
    message(FATAL_ERROR "Run python tools/prepare_glm_formula.py before building")
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)
foreach(ocr_target ggml-base ggml-cpu llama mtmd)
    target_compile_options(${ocr_target} PRIVATE -O3)
endforeach()
add_library(xnotesformula SHARED formula_jni.cpp)
target_compile_features(xnotesformula PRIVATE cxx_std_17)
target_link_libraries(xnotesformula PRIVATE mtmd llama log)
target_link_options(xnotesformula PRIVATE ${XNOTES_LINK_OPTIONS})
