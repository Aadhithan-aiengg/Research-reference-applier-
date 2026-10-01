import time
import pyperclip
import keyboard
import pyautogui
import ctypes
from tkinter import Tk
from tkinter.filedialog import askdirectory
import os
from pathlib import Path

# Import citation extraction functions
from pdfminer.high_level import extract_text
import re
import spacy
from spacy.matcher import Matcher
import sys
import io


# Initialize NLP
nlp = spacy.load("en_core_web_sm")
matcher1 = Matcher(nlp.vocab)

AUTHOR_LIST_REGEX = r"[A-Z]+(?:\s[A-Z]+)*\s\d+(?:,\s[A-Z]+(?:\s[A-Z]+)*\s\d+)*[\.,\s]*"

pattern_tokens = [
    {"TEXT": {"REGEX": r"^[A-Z][a-zA-Z]*$"}, "OP": "+"},
    {"TEXT": {"REGEX": r"^(Nagar|Vihar|Terms)$"}}
]
matcher1.add("GPE", [pattern_tokens])


# ==================== CITATION EXTRACTION FUNCTIONS ==================== #

def extract_text_from_first_page(pdf_file):
    """Extract text from the first page of a PDF"""
    text = extract_text(pdf_file, page_numbers=[0])
    return text


def extract_title(text, names=None):
    """Extract title from IEEE paper with multiple strategies"""
    lines = [l.strip() for l in text.split('\n') if l.strip()]

    ieee_patterns = [
        r'IEEE\s+TRANSACTIONS?',
        r'IEEE\s+JOURNAL',
        r'IEEE\s+CONFERENCE',
        r'PROCEEDINGS\s+OF\s+THE\s+IEEE',
        r'IEEE\s+ACCESS',
        r'IEEE\s+COMMUNICATIONS',
        r'IEEE\s+ROBOTICS',
        r'IEEE\s+SENSORS'
    ]

    title_start_idx = -1
    for i, line in enumerate(lines):
        if any(re.search(pattern, line, re.IGNORECASE) for pattern in ieee_patterns):
            title_start_idx = i + 1
            break

    if title_start_idx == -1:
        for i, line in enumerate(lines):
            if re.search(r'\b(doi|vol\.|pp\.|received|accepted|manuscript|digital object identifier)\b', line,
                         re.IGNORECASE):
                continue

            if re.search(r'©\s*\d{4}|^\d{4}\s|date of', line, re.IGNORECASE):
                continue

            if len(line.split()) >= 3 and not re.match(r'^[\W\d_]+$', line):
                title_start_idx = i
                break

    if title_start_idx == -1:
        title_start_idx = 0

    title_lines = []
    stop_keywords = [
        r'\babstract\b', r'\bauthor', r'\bindex terms\b', r'\bkeywords\b',
        r'\bintroduction\b', r'^I\.\s+', r'\bmember,?\s+ieee\b', r'\bfellow,?\s+ieee\b',
        r'\bsenior member\b', r'\buniversity\b', r'\bdepartment\b', r'\bcollege\b',
        r'\binstitute\b', r'\blaboratory\b', r'@', r'\.edu\b', r'\.com\b'
    ]

    for i in range(title_start_idx, min(title_start_idx + 8, len(lines))):
        line = lines[i]

        if re.fullmatch(AUTHOR_LIST_REGEX, line.strip()):
            break

        line = re.sub(AUTHOR_LIST_REGEX, '', line).strip()

        if any(re.search(pattern, line, re.IGNORECASE) for pattern in stop_keywords):
            break

        if re.search(r'(manuscript|revised|accepted|published|digital object identifier|©\s*\d{4}|^\d{4}\s)', line,
                     re.IGNORECASE):
            continue

        if re.match(r'^[\W\d_\-—]+$', line):
            continue

        if len(line.split()) < 2:
            continue

        if line.isupper() and len(line.split()) > 8:
            continue

        title_lines.append(line)

        if len(title_lines) >= 3:
            break

    title = ' '.join(title_lines).strip()
    title = re.sub(r'\s+', ' ', title)
    title = re.sub(r'[\*†‡]', '', title)

    if names:
        for name in names:
            pattern = re.escape(name)
            title = re.sub(rf'\b{pattern}\b', '', title, flags=re.IGNORECASE)

    title = re.sub(r'\s{2,}', ' ', title).strip()

    return title


def extract_year(text):
    """Extract publication year with priority for copyright/publication dates"""
    copyright_match = re.search(r'©\s*(\d{4})\s+IEEE', text)
    if copyright_match:
        return copyright_match.group(1)

    pub_patterns = [
        r'(?:published|accepted|revised).*?(\d{4})',
        r'(?:january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{4})',
        r'vol\.\s*\d+.*?(\d{4})'
    ]

    for pattern in pub_patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            year = match.group(1)
            if 1950 <= int(year) <= 2030:
                return year

    years = re.findall(r'\b(19[5-9]\d|20[0-3]\d)\b', text)
    if years:
        return years[0]

    return "Year not found"


def extract_names(text):
    """Extract author names from IEEE paper using pattern matching + NER"""
    lines = text.split('\n')

    author_start = -1
    author_end = -1

    for i, line in enumerate(lines):
        if re.search(r'\b(member|fellow|senior member),?\s+ieee\b', line, re.IGNORECASE):
            if author_start == -1:
                author_start = max(0, i - 3)

        if re.search(r'\b(abstract|index terms|keywords|introduction|^I\.\s+INTRODUCTION)\b', line, re.IGNORECASE):
            if author_start != -1:
                author_end = i
                break

    if author_start != -1 and author_end != -1:
        author_text = '\n'.join(lines[author_start:author_end])
    else:
        author_text = '\n'.join(lines[5:30])

    author_text = re.sub(r',\s*\bAnd\s+[A-Za-z]+\s*,', ',', author_text, flags=re.IGNORECASE)

    doc = nlp(author_text)

    matches = matcher1(doc)
    to_exclude = set()
    for match_id, start, end in matches:
        span = doc[start:end]
        to_exclude.add(span.text.lower())

    names = []
    seen = set()

    caps_name_pattern = r'\b([A-Z][A-ZÀ-Ž\-]+(?:\s+[A-Z]\.?\s*)+[A-Z][A-ZÀ-Ž\-]+)\b'
    caps_matches = re.findall(caps_name_pattern, author_text)
    for name in caps_matches:
        name_clean = name.strip()
        name_clean = ' '.join(w.capitalize() for w in name_clean.split())
        if name_clean.lower() not in seen and len(name_clean.split()) >= 2:
            seen.add(name_clean.lower())
            names.append(name_clean)

    for ent in doc.ents:
        if ent.label_ == "PERSON":
            name = ent.text.strip()
            name_clean = re.sub(r'[^\w\s\-\.]', '', name)

            if (
                    name_clean.lower() in seen
                    or len(name_clean.split()) < 2
                    or len(name_clean) < 5
                    or re.search(r'\d', name_clean)
                    or name_clean.lower().startswith('ieee')
                    or re.search(
                r'\b(university|college|department|institute|laboratory|and|campus|informática|terms)\b',
                name_clean,
                re.IGNORECASE,
            )
                    or any(name_clean.lower().endswith(x) for x in [" nagar", " vihar", " terms"])
                    or name_clean.lower() in to_exclude
            ):
                continue

            seen.add(name_clean.lower())
            names.append(name_clean)

    names = [name for name in names if not re.match(r'\band\w*\b', name, re.IGNORECASE)]

    return names


def return_info(pdf_file):
    """Extract title, year, and author names from an IEEE paper"""
    try:
        text = extract_text_from_first_page(pdf_file)

        print("\n--- Raw Text (first 1500 chars) ---")
        print(text[:1500])
        print("\n--- End Raw Text ---\n")

        names = extract_names(text)
        title = extract_title(text, names)
        year = extract_year(text)

        print("\n=== Extracted Metadata ===")
        print(f"Authors ({len(names)}): {', '.join(names[:10])}")

        if len(names) > 10:
            print(f"  ... and {len(names) - 10} more")

        return {
            "title": title,
            "year": year,
            "names": names
        }

    except Exception as e:
        print(f"Error processing PDF: {e}")
        return {
            "title": "Error extracting title",
            "year": "Error extracting year",
            "names": []
        }


# ==================== WINDOW TITLE + PDF EXTRACTION ==================== #

def get_active_window_title():
    """Get the window title of the active window."""
    hwnd = ctypes.windll.user32.GetForegroundWindow()
    length = ctypes.windll.user32.GetWindowTextLengthW(hwnd)
    buff = ctypes.create_unicode_buffer(length + 1)
    ctypes.windll.user32.GetWindowTextW(hwnd, buff, length + 1)
    return buff.value


def extract_pdf_filename(window_title):
    """Extract filename.pdf from a window title."""
    lower_title = window_title.lower()
    pdf_index = lower_title.find('.pdf')

    if pdf_index != -1:
        clean_name = window_title[:pdf_index + 4]

        if '\\' in clean_name:
            clean_name = clean_name.split('\\')[-1]

        return clean_name

    return None


# ==================== CAPTURE SELECTED TEXT ==================== #

def capture_selected_text():
    """Capture selected text + PDF filename from the active window."""
    pyperclip.copy("")
    pyautogui.hotkey('ctrl', 'c')
    time.sleep(0.15)
    selected_text = pyperclip.paste()

    if not selected_text.strip():
        print("No text selected.")
        return None, None

    window_title = get_active_window_title()
    pdf_name = extract_pdf_filename(window_title)

    print("--------------------------------------------------")
    print(f"SOURCE:  {pdf_name}")
    print(f"CONTENT: {selected_text}")
    print("--------------------------------------------------")

    return selected_text, pdf_name


# ==================== DIRECTORY SELECTION ==================== #

def fetchdir():
    """Ask user to pick a directory."""
    root = Tk()
    root.withdraw()
    directory = askdirectory(title="Select a directory")
    root.destroy()
    return directory


# ==================== HOTKEY HANDLER ==================== #

pdf_name = None
dirpath = None
full_pdf_path = None


def capture_and_store():
    """Capture PDF name and selected text when 'g' is pressed."""
    global pdf_name, full_pdf_path

    _, pdf_name = capture_selected_text()

    if pdf_name and dirpath:
        full_pdf_path = str(Path(dirpath) / pdf_name)
        print(f"[OK] PDF Name Captured: {pdf_name}")
        print(f"[PATH] Full Path: {full_pdf_path}")

        # Now extract citation information from the PDF
        print("\n[EXTRACTING] Citation information from PDF...")
        citation_info = return_info(full_pdf_path)

        print(f"Title: {citation_info['title']}", flush=True)
        print(f"Year: {citation_info['year']}", flush=True)
        print(f"Authors: {', '.join(citation_info['names'])}", flush=True)
        print(f"{citation_info['title']},{citation_info['year']},{', '.join(citation_info['names'])}",flush=True)

    elif pdf_name:
        print(f"[!] PDF Name Captured: {pdf_name}")
        print("[!] But directory not set. Cannot create full path.")
    else:
        print("[!] Could not extract PDF filename.")


def start_hotkey_monitoring():
    print("\n--- Capture Tool Running ---")
    print("Highlight text and press **G** to capture citation")
    print("Press ESC to quit.\n")

    keyboard.add_hotkey('g', capture_and_store)

    try:
        keyboard.wait('esc')
    except KeyboardInterrupt:
        pass


# ==================== MAIN ==================== #
if __name__ == '__main__':
    # If Java gives directory -> use it
    if len(sys.argv) > 1:
        dirpath = sys.argv[1]
        print(f"[DIR] Using directory from Java: {dirpath}\n")
    else:
        print("Please select a directory containing your PDF files:")
        dirpath = fetchdir()

    if not dirpath:
        print("[ERROR] No directory provided. Exiting.")
        sys.exit(1)

    start_hotkey_monitoring()