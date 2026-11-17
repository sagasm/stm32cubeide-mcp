package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class ListProjectsToolTest {

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsAProjectCreatedByTheTest() throws Exception {
		fixture.createJavaProject("mcp-list-projects-test");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects", Map.of());
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		Map<String, Object> project = projects.stream()
				.filter(entry -> "mcp-list-projects-test".equals(entry.get("name"))).findFirst()
				.orElseThrow(() -> new AssertionError("Test project not reported, got " + projects));

		assertEquals(Boolean.TRUE, project.get("open"));
		assertEquals("model", project.get("natureSource"));
		assertTrue(((List<String>) project.get("natures")).contains("org.eclipse.jdt.core.javanature"),
				"Java nature missing in " + project);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsTheNaturesOfAClosedProjectFromItsProjectFile() throws Exception {
		IProject project = fixture.createJavaProject("mcp-list-closed-test").getProject();
		project.close(new NullProgressMonitor());

		Map<String, Object> entry = find("mcp-list-closed-test");

		assertEquals(Boolean.FALSE, entry.get("open"));
		// the Java model cannot answer for a closed project, and answering "none"
		// makes a client's classification depend on which projects happen to be open
		assertEquals("projectFile", entry.get("natureSource"));
		assertTrue(((List<String>) entry.get("natures")).contains("org.eclipse.jdt.core.javanature"),
				"the nature is in .project on disk whether or not the project is open, got " + entry);
	}

	@Test
	@SuppressWarnings("unchecked")
	void summaryCountsWithoutListing() throws Exception {
		fixture.createJavaProject("mcp-list-summary-open");
		fixture.createJavaProject("mcp-list-summary-closed").getProject().close(new NullProgressMonitor());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects", Map.of("summary", Boolean.TRUE));

		assertFalse(result.containsKey("projects"), "got " + result);
		int total = ((Number) result.get("total")).intValue();
		int open = ((Number) result.get("open")).intValue();
		int closed = ((Number) result.get("closed")).intValue();
		assertEquals(total, open + closed, "got " + result);
		assertTrue(open >= 1 && closed >= 1, "got " + result);
		// the closed project's nature is read from disk, so both are counted
		Map<String, Object> byNature = (Map<String, Object>) result.get("byNature");
		assertTrue(((Number) byNature.get("org.eclipse.jdt.core.javanature")).intValue() >= 2, "got " + result);
	}

	@Test
	@SuppressWarnings("unchecked")
	void filtersByNatureAsStringAndArray() throws Exception {
		String javaNature = "org.eclipse.jdt.core.javanature";
		fixture.createJavaProject("mcp-list-nature-java");
		fixture.createProject("mcp-list-nature-plain");

		Map<String, Object> single = TestFixture.callAndParse("eclipse_list_projects",
				Map.of("natureFilter", javaNature, "namePattern", "mcp-list-nature-*"));
		List<Map<String, Object>> projects = (List<Map<String, Object>>) single.get("projects");
		assertEquals(1, projects.size(), "got " + projects);
		assertEquals("mcp-list-nature-java", projects.get(0).get("name"));

		Map<String, Object> array = TestFixture.callAndParse("eclipse_list_projects",
				Map.of("natureFilter", List.of(javaNature, "org.eclipse.cdt.core.cnature"),
						"namePattern", "mcp-list-nature-*"));
		assertEquals(Integer.valueOf(1), array.get("total"), "got " + array);
	}

	@Test
	void openOnlyDropsClosedProjects() throws Exception {
		fixture.createJavaProject("mcp-list-openonly-open");
		fixture.createJavaProject("mcp-list-openonly-closed").getProject().close(new NullProgressMonitor());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects",
				Map.of("openOnly", Boolean.TRUE, "namePattern", "mcp-list-openonly-*"));

		assertEquals(Integer.valueOf(1), result.get("total"), "got " + result);
		assertTrue(((Number) result.get("workspaceTotal")).intValue() >= 2, "got " + result);
	}

	@Test
	void namePatternIsACaseInsensitiveGlob() throws Exception {
		fixture.createProject("mcp-list-glob-Alpha");
		fixture.createProject("mcp-list-glob-beta");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects",
				Map.of("namePattern", "MCP-LIST-GLOB-A*"));

		assertEquals(Integer.valueOf(1), result.get("total"), "got " + result);
	}

	@Test
	@SuppressWarnings("unchecked")
	void stateMarkerReportsTheBeforeAndAfterOfAStateChange() throws Exception {
		IProject project = fixture.createProject("mcp-list-marker-state");

		Map<String, Object> change = TestFixture.callAndParse("eclipse_set_project_state",
				Map.of("state", "closed", "projects", List.of(project.getName()), "dryRun", Boolean.FALSE));
		String marker = String.valueOf(change.get("stateMarker"));
		assertTrue(marker.startsWith("mcpprojstate-"), "got " + marker);

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects",
				Map.of("stateMarker", marker, "namePattern", project.getName()));

		assertEquals(Boolean.TRUE, result.get("sinceStateMarker"));
		assertEquals(Integer.valueOf(1), result.get("stateChangedCount"), "got " + result);
		List<Map<String, Object>> changes = (List<Map<String, Object>>) result.get("stateChanges");
		assertEquals(1, changes.size(), "got " + result);
		assertEquals(project.getName(), changes.get(0).get("name"));
		assertEquals("open", changes.get(0).get("from"));
		assertEquals("closed", changes.get(0).get("to"));
	}

	@Test
	void rejectsAnUnknownStateMarker() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_list_projects",
				Map.of("stateMarker", "mcpprojstate-999999"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("set_project_state"), result.text());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> find(String name) throws Exception {
		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects", Map.of());
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		return projects.stream().filter(entry -> name.equals(entry.get("name"))).findFirst()
				.orElseThrow(() -> new AssertionError("Test project not reported, got " + projects));
	}
}
