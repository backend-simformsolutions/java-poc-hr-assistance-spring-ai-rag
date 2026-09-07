package com.example.hrbot;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a synthetic HR policy handbook to exercise the ingestion and retrieval
 * pipeline, written to {@code Hr Policy/sample-hr-policy.pdf}.
 *
 * <p>Its contents are invented. It exists so the project can be demonstrated end to end
 * without ingesting real policy documents.
 *
 * <p>Run it as a plain {@code main} method — from an IDE, or with
 * {@code mvn exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=com.example.hrbot.GenerateSampleHrPolicyPdf}.
 */
public class GenerateSampleHrPolicyPdf {

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 50f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float HEADING_SIZE = 16f;
    private static final float SUBHEADING_SIZE = 13f;
    private static final float BODY_SIZE = 11f;
    private static final float LINE_HEIGHT = 16f;

    /** Folder holding the policy documents this project ingests. */
    private static final Path DOCUMENTS_DIR = Paths.get("Hr Policy");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(DOCUMENTS_DIR);
        Path outputPath = DOCUMENTS_DIR.resolve("sample-hr-policy.pdf");

        System.out.println("Generating sample HR policy PDF...");

        try (PDDocument doc = new PDDocument()) {

            // Section 1: leave and time off
            addPage(doc, "TECHCORP HR POLICY HANDBOOK", List.of(
                "Effective Date: January 1, 2024",
                "Version: 3.2 | HR Department | hr@techcorp.com",
                "",
                "SECTION 1: LEAVE AND TIME OFF POLICY",
                "",
                "1.1 Annual Leave (Paid Time Off)",
                "All full-time employees are entitled to 20 days of annual paid leave per",
                "calendar year. Part-time employees receive leave on a pro-rata basis.",
                "Leave entitlement accrues at a rate of 1.67 days per month of service.",
                "Unused annual leave up to 10 days may be carried forward to the following",
                "year but must be used within the first quarter (by March 31).",
                "Employees must submit leave requests at least 5 business days in advance",
                "through the HR portal. Requests are subject to manager approval.",
                "",
                "1.2 Sick Leave",
                "Employees are entitled to 12 days of sick leave per year. Sick leave is",
                "non-cumulative and cannot be carried forward. For absences exceeding 3",
                "consecutive days, a medical certificate from a registered physician is",
                "required. Sick leave does not apply to pre-planned medical procedures,",
                "which should be taken as planned medical leave.",
                "",
                "1.3 Casual Leave",
                "Each employee receives 6 days of casual leave per year for personal",
                "emergencies or short-notice absences. Casual leave must be applied for",
                "before or on the same day of absence. Casual leave cannot be clubbed",
                "with other leave types without prior written approval from HR.",
                "",
                "1.4 Maternity and Paternity Leave",
                "Maternity Leave: Female employees are entitled to 26 weeks of paid",
                "maternity leave for the first two children. For subsequent children, the",
                "entitlement is 12 weeks. Leave can commence up to 8 weeks before the",
                "expected date of delivery.",
                "Paternity Leave: Male employees are entitled to 10 working days of",
                "paid paternity leave, to be taken within 3 months of the child's birth.",
                "",
                "1.5 Bereavement Leave",
                "Employees may take up to 5 paid bereavement leave days for the death",
                "of an immediate family member (spouse, child, parent, or sibling).",
                "Up to 3 days for extended family members (grandparents, in-laws).",
                "Proof of relationship may be requested by HR.",
                "",
                "1.6 Public Holidays",
                "Employees are entitled to all gazetted public holidays (approximately 14",
                "per year). If an employee is required to work on a public holiday, they",
                "will receive either a replacement day off or 1.5x their daily rate."
            ));

            // Sections 2-3: remote work, then code of conduct
            addPage(doc, "SECTION 2: REMOTE WORK AND FLEXIBLE WORK POLICY", List.of(
                "",
                "2.1 Work From Home (WFH) Eligibility",
                "All permanent employees who have completed their 3-month probation period",
                "are eligible for remote work arrangements. Employees on performance",
                "improvement plans are not eligible for WFH until the plan is closed.",
                "",
                "2.2 WFH Schedule",
                "The standard remote work allowance is up to 3 days per week from home.",
                "Employees must be present in the office a minimum of 2 days per week,",
                "unless otherwise approved by their department head. Mondays and Fridays",
                "are not guaranteed WFH days and depend on team requirements.",
                "",
                "2.3 Remote Work Equipment",
                "The company provides a laptop and necessary software for remote work.",
                "Employees are responsible for a stable internet connection (minimum",
                "25 Mbps recommended). A monthly internet allowance of $30 is provided.",
                "Company equipment must not be used for personal commercial activities.",
                "Lost or damaged company equipment due to negligence will be charged",
                "to the employee at the current replacement cost.",
                "",
                "2.4 Availability During Remote Work",
                "Remote employees must be available and online during core hours:",
                "10:00 AM to 4:00 PM in their local time zone. Response to messages",
                "must be within 30 minutes during core hours. Employees must attend",
                "all scheduled meetings and standups virtually.",
                "",
                "SECTION 3: CODE OF CONDUCT",
                "",
                "3.1 Professional Behavior",
                "All employees are expected to conduct themselves professionally at all",
                "times — whether on-site, at client locations, or in online interactions.",
                "This includes respectful communication, appropriate attire, and",
                "maintaining confidentiality of business information.",
                "",
                "3.2 Anti-Harassment Policy",
                "TechCorp has zero tolerance for harassment of any kind, including",
                "sexual harassment, bullying, discrimination based on race, gender,",
                "religion, nationality, age, disability, or any other protected class.",
                "All employees are encouraged to report incidents to their HR Business",
                "Partner or via the anonymous ethics hotline.",
                "",
                "3.3 Conflict of Interest",
                "Employees must disclose any potential conflicts of interest to their",
                "manager and HR in writing. This includes holding positions in competing",
                "companies, doing freelance work for clients of TechCorp, or having",
                "personal relationships with vendors or suppliers.",
                "",
                "3.4 Dress Code",
                "The office follows a smart-casual dress code. Formal business attire is",
                "required when meeting clients or attending official events. Jeans and",
                "casual wear are acceptable on Fridays (Casual Fridays).",
                "Clothing with offensive language, symbols, or graphic imagery is strictly prohibited."
            ));

            // Sections 4-5: compensation and benefits
            addPage(doc, "SECTION 4: COMPENSATION AND BENEFITS", List.of(
                "",
                "4.1 Salary Payment",
                "Salaries are paid on the last working day of each month via direct bank",
                "transfer. Payslips are available in the employee self-service portal by",
                "the 28th of each month. Any salary queries must be raised with payroll",
                "by the 20th to be reflected in the current month's payment.",
                "",
                "4.2 Performance Bonus",
                "Annual performance bonuses are awarded based on individual performance",
                "ratings and company profitability. The bonus cycle is January to December.",
                "Payouts occur in March of the following year.",
                "Bonus Range based on performance rating:",
                "  - Exceeds Expectations (5): 20-30% of annual salary",
                "  - Meets All Expectations (4): 10-15% of annual salary",
                "  - Meets Expectations (3): 5-8% of annual salary",
                "  - Needs Improvement (2): No bonus",
                "  - Unsatisfactory (1): No bonus",
                "",
                "4.3 Health Insurance",
                "All full-time employees and their immediate family (spouse + up to 2",
                "children under 25) are covered under the company group health insurance.",
                "The coverage includes hospitalization, outpatient consultations, dental,",
                "and optical benefits up to specified annual limits. The company pays",
                "100% of the premium for the employee; family coverage is shared 80/20.",
                "",
                "4.4 Provident Fund (PF) and Pension",
                "Employee contribution: 12% of basic salary (pre-tax)",
                "Employer contribution: 12% of basic salary",
                "Contributions are made monthly and are fully vested after 2 years.",
                "Employees may voluntarily increase their contribution up to 100% of salary.",
                "",
                "4.5 Travel and Expense Reimbursement",
                "Business travel expenses are reimbursed upon submission of original",
                "receipts within 30 days of travel. Expense limits:",
                "  - Domestic flights: Economy class",
                "  - International flights: Business class for journeys over 6 hours",
                "  - Hotels: Up to $150/night domestic, $250/night international",
                "  - Meals per diem: $50 domestic, $80 international",
                "  - Local transport: Actual cost (cab/rideshare receipts required)",
                "",
                "4.6 Learning and Development Allowance",
                "Each employee receives an annual L&D budget of $1,500 for training,",
                "certifications, conferences, or online courses. Requests must be pre-",
                "approved by your line manager. The company will sponsor professional",
                "certifications relevant to your role (e.g., AWS, PMP, CFA) up to $500",
                "per year with a 12-month service bond post-certification."
            ));

            // Sections 6-7: performance management, grievances and exit
            addPage(doc, "SECTION 5: PERFORMANCE MANAGEMENT", List.of(
                "",
                "5.1 Performance Review Cycle",
                "TechCorp uses a continuous performance management framework with:",
                "  - Quarterly Check-ins: Informal conversations between employee and manager",
                "  - Mid-Year Review (July): Formal written feedback, goal adjustment",
                "  - Annual Appraisal (December): Full performance and compensation review",
                "All reviews are conducted via the PerformanceHub portal.",
                "",
                "5.2 Goal Setting (OKRs)",
                "At the start of each year, employees set 3-5 Objectives with 2-3 Key",
                "Results each. Goals must follow the SMART framework (Specific, Measurable,",
                "Achievable, Relevant, Time-bound). Goals are cascaded from company to",
                "team to individual to ensure alignment.",
                "",
                "5.3 Performance Improvement Plan (PIP)",
                "If an employee consistently does not meet expectations, HR and the",
                "manager will initiate a 60-day Performance Improvement Plan (PIP).",
                "The PIP outlines specific measurable targets. Failure to meet PIP",
                "targets may result in termination of employment.",
                "",
                "5.4 360-Degree Feedback",
                "Twice a year, employees give and receive anonymous feedback from up",
                "to 8 peers, direct reports, and cross-functional partners. This",
                "feedback is used for developmental purposes only and does not",
                "directly influence compensation decisions.",
                "",
                "SECTION 6: GRIEVANCE AND ESCALATION POLICY",
                "",
                "6.1 Raising a Grievance",
                "Employees who have a workplace grievance should first attempt to",
                "resolve it informally by speaking with their direct manager.",
                "If unresolved within 5 business days, a formal grievance can be",
                "submitted to HR via the employee portal. HR will acknowledge",
                "receipt within 2 business days and begin investigation.",
                "",
                "6.2 Grievance Investigation",
                "A full investigation will be completed within 20 business days.",
                "Both parties will be heard. The outcome will be communicated in",
                "writing. Appeals to the resolution can be made to the HR Director",
                "within 5 business days of the decision.",
                "",
                "6.3 Whistleblower Protection",
                "Employees who report misconduct, fraud, or policy violations in",
                "good faith are protected from retaliation under this policy.",
                "Reports may be made anonymously via the ethics hotline:",
                "Phone: 1-800-ETHICS-1 | Web: ethics.techcorp.com",
                "",
                "SECTION 7: SEPARATION AND EXIT POLICY",
                "",
                "7.1 Notice Period",
                "  - Probation (first 3 months): 1 week notice",
                "  - 3 months to 2 years: 4 weeks notice",
                "  - 2 years to 5 years: 6 weeks notice",
                "  - Over 5 years: 8 weeks notice",
                "The company may pay in lieu of notice at its discretion.",
                "",
                "7.2 Exit Clearance",
                "Before the last day, employees must return all company property,",
                "complete a knowledge transfer document, conduct a handover session",
                "with their successor or manager, and complete the exit interview."
            ));

            doc.save(outputPath.toFile());
            System.out.println("Written to " + outputPath.toAbsolutePath());
            System.out.println("Upload it with: POST /api/documents/upload");
        }
    }

    /**
     * Adds a new page with a heading and body lines to the document.
     * Automatically wraps long lines and adds new pages when content overflows.
     */
    private static void addPage(PDDocument doc, String heading, List<String> lines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        PDType1Font headingFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font subheadingFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        PDPageContentStream cs = new PDPageContentStream(doc, page);
        float y = PAGE_HEIGHT - MARGIN;

        cs.beginText();
        cs.setFont(headingFont, HEADING_SIZE);
        cs.newLineAtOffset(MARGIN, y);
        cs.showText(heading);
        cs.endText();
        y -= HEADING_SIZE + 8;

        cs.moveTo(MARGIN, y);
        cs.lineTo(PAGE_WIDTH - MARGIN, y);
        cs.stroke();
        y -= 12;

        float currentY = y;
        PDPageContentStream currentCs = cs;

        for (String line : lines) {
            boolean isSection = line.startsWith("SECTION");
            boolean isNumbered = line.matches("^\\d+\\.\\d+.*") || line.matches("^\\d+\\..*[A-Z].*");
            PDType1Font font = isSection ? headingFont : (isNumbered ? subheadingFont : bodyFont);
            float size = isSection ? SUBHEADING_SIZE : (isNumbered ? BODY_SIZE + 0.5f : BODY_SIZE);

            // Check if we need a new page (leave some bottom margin)
            if (currentY < MARGIN + 40) {
                currentCs.close();
                PDPage newPage = new PDPage(PDRectangle.A4);
                doc.addPage(newPage);
                currentCs = new PDPageContentStream(doc, newPage);
                currentY = PAGE_HEIGHT - MARGIN;
            }

            if (line.isEmpty()) {
                currentY -= LINE_HEIGHT * 0.6f;
                continue;
            }

            List<String> wrappedLines = wrapText(line, font, size, CONTENT_WIDTH);
            for (String wl : wrappedLines) {
                if (currentY < MARGIN + 40) {
                    currentCs.close();
                    PDPage newPage = new PDPage(PDRectangle.A4);
                    doc.addPage(newPage);
                    currentCs = new PDPageContentStream(doc, newPage);
                    currentY = PAGE_HEIGHT - MARGIN;
                }
                currentCs.beginText();
                currentCs.setFont(font, size);
                currentCs.newLineAtOffset(MARGIN, currentY);
                currentCs.showText(wl);
                currentCs.endText();
                currentY -= LINE_HEIGHT;
            }
        }
        currentCs.close();
    }

    /**
     * Word-wraps text to fit within maxWidth.
     */
    private static List<String> wrapText(String text, PDType1Font font, float fontSize, float maxWidth)
            throws IOException {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.isEmpty() ? word : currentLine + " " + word;
            float width = font.getStringWidth(testLine) / 1000 * fontSize;
            if (width > maxWidth && !currentLine.isEmpty()) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                currentLine = new StringBuilder(testLine);
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }
}


