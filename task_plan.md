# Steam Workshop Dependencies Analyzer - Implementation Plan

## Project Overview
A tool for analyzing and visualizing Steam Workshop item dependencies, initially supporting Project Zomboid game mods.

## Goals
- Build a dependency analyzer that can parse workshop.txt files
- Create dependency trees and graphs
- Support reverse dependency lookup
- Provide visualization options
- Initially focus on Project Zomboid, with extensibility for other games

## Phases

### Phase 1: Project Setup and Basic Models
- [ ] Initialize project structure
- [ ] Define core data models (WorkshopItem, DependencyNode, DependencyGraph)
- [ ] Set up basic configuration
- [ ] Create directory structure following the architecture diagram

### Phase 2: Data Access Layer
- [ ] Implement LocalWorkshopScanner to scan workshop directories
- [ ] Create ConfigParser to parse workshop.txt and mod.info files
- [ ] Implement WorkshopCacheManager for caching parsed data
- [ ] Add error handling for parsing operations

### Phase 3: Business Logic Layer
- [ ] Implement WorkshopItemResolver to coordinate data access
- [ ] Build DependencyAnalyzer with dependency tree building
- [ ] Add reverse dependency lookup functionality
- [ ] Implement circular dependency detection

### Phase 4: API Integration (Optional)
- [ ] Create SteamWebAPIClient for Steam Web API integration
- [ ] Add ability to fetch online item information
- [ ] Implement fallback mechanism (local → API)

### Phase 5: Command Line Interface
- [ ] Create CLI commands (analyze, reverse-deps, check-cycles, export-graph)
- [ ] Implement argument parsing
- [ ] Add output formatting options
- [ ] Create usage documentation

### Phase 6: Testing
- [ ] Write unit tests for data models
- [ ] Create tests for parsing functionality
- [ ] Add integration tests for dependency analysis
- [ ] Test with sample workshop data

### Phase 7: Documentation
- [ ] Update README with usage instructions
- [ ] Document API and CLI usage
- [ ] Add troubleshooting guide
- [ ] Include examples

## Success Criteria
- [ ] Can parse workshop.txt and mod.info files correctly
- [ ] Builds accurate dependency trees up to 10 levels deep
- [ ] Detects circular dependencies properly
- [ ] Finds reverse dependencies efficiently
- [ ] CLI tool runs without errors
- [ ] All tests pass

## Timeline
Target completion: 2 weeks with iterative development

## Risks
- Parsing variations in workshop.txt formats across different games
- Large dependency trees causing performance issues
- Steam Web API rate limiting
- Circular dependency edge cases

## Resources Needed
- Sample workshop.txt files for testing
- Access to Steam Workshop directory structure
- Swift development environment

